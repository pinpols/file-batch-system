package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.NodePartitionAssignment;
import io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 负责 worker 回报后的分区、实例和 workflow 聚合推进。
 *
 * <p>task 终态写入以后，分区状态、实例进度、节点切换、父任务回报和 workflow 终态必须按固定顺序
 * 在同一 report 事务中完成。将这段聚合编排从入口服务下沉后，入口只保留 report 的身份校验、task
 * CAS 和 step 镜像更新；本类仍不声明独立事务，避免把一次回报拆成多个提交单元。
 */
@Component
@Slf4j
public final class TaskOutcomeInstanceProgressor {

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final DefaultTaskOutcomeService.DefaultTaskOutcomeCollaborators collaborators;
  private final TaskOutcomeTerminalFinalizer terminalFinalizer;
  private final TaskOutcomeDagProgressor dagProgressor;
  private final TaskOutcomeParentTaskSignaler parentTaskSignaler;
  private final TaskOutcomeWorkflowFinalizer workflowFinalizer;

  public TaskOutcomeInstanceProgressor(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      DefaultTaskOutcomeService.DefaultTaskOutcomeCollaborators collaborators,
      TaskOutcomeTerminalFinalizer terminalFinalizer,
      TaskOutcomeDagProgressor dagProgressor,
      TaskOutcomeParentTaskSignaler parentTaskSignaler,
      TaskOutcomeWorkflowFinalizer workflowFinalizer) {
    this.jobMappers = jobMappers;
    this.workflowMappers = workflowMappers;
    this.collaborators = collaborators;
    this.terminalFinalizer = terminalFinalizer;
    this.dagProgressor = dagProgressor;
    this.parentTaskSignaler = parentTaskSignaler;
    this.workflowFinalizer = workflowFinalizer;
  }

  void advance(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      Instant finishedAt,
      Consumer<TaskOutcomeCommand> parentOutcomeApplier) {
    List<PartitionStatusRef> statusRefs = jobMappers.jobPartitionMapper.selectStatusRefsByInstance(
        command.tenantId(), task.getJobInstanceId());
    long successCount = statusRefs.stream()
        .filter(r -> PartitionStatus.SUCCESS.code().equals(r.partitionStatus()))
        .count();
    long failedCount = statusRefs.stream()
        .filter(r -> PartitionStatus.FAILED.code().equals(r.partitionStatus()))
        .count();
    long finishedPartitionCount = successCount + failedCount;
    boolean allPartitionsFinished =
        EmptyChecks.isNotEmpty(statusRefs) && finishedPartitionCount == statusRefs.size();
    WorkflowRunEntity workflowRun = workflowMappers.workflowRunMapper.selectByRelatedJobInstanceId(
        command.tenantId(), jobInstance.getId());
    String currentNodeCode = resolveCurrentNodeCode(task, workflowRun);
    List<NodePartitionAssignment> nodeAssignments =
        jobMappers.jobTaskMapper.selectNodeAssignmentsByInstance(
            command.tenantId(), task.getJobInstanceId());
    NodePartitionProgressCalculator.Result nodeProgress = NodePartitionProgressCalculator.calculate(
        statusRefs, nodeAssignments, currentNodeCode, workflowRun);
    Set<String> activeNodes = EmptyChecks.isNull(workflowRun)
        ? new LinkedHashSet<>()
        : TaskOutcomeStatePolicy.parseActiveNodes(workflowRun.getCurrentNodeCode());

    if (nodeProgress.allFinished() && EmptyChecks.isNotNull(workflowRun)) {
      List<JobPartitionEntity> nodeCompletionPartitions = loadPartitions(command, task);
      TaskOutcomeDagProgressor.Context advanceContext = TaskOutcomeDagProgressor.Context.builder()
          .command(command)
          .task(task)
          .jobInstance(jobInstance)
          .workflowRun(workflowRun)
          .currentNodeCode(currentNodeCode)
          .nodeProgress(nodeProgress)
          .nodeOutputs(TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(
              TaskOutcomeSummaryBuilder.filterPartitionsByIds(
                  nodeCompletionPartitions, nodeProgress.partitionIds()),
              command))
          .activeNodes(activeNodes)
          .finishedAt(finishedAt)
          .build();
      dagProgressor.advance(advanceContext);
      // 派发期游标可能残留已完成的 FORK；以最新 node_run 重建活跃集合，避免 END 成功后实例永久 RUNNING。
      activeNodes.clear();
      activeNodes.addAll(TaskOutcomeStatePolicy.resolveActiveNodeCodes(
          workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId())));
    }

    boolean dagContinues =
        EmptyChecks.isNotNull(workflowRun) && EmptyChecks.isNotEmpty(activeNodes);
    boolean jobFullyComplete = allPartitionsFinished && !dagContinues;
    JobInstanceEntity freshInstance =
        jobMappers.jobInstanceMapper.selectById(command.tenantId(), jobInstance.getId());
    if (EmptyChecks.isNotNull(freshInstance)) {
      jobInstance.setVersion(freshInstance.getVersion());
      jobInstance.setInstanceStatus(freshInstance.getInstanceStatus());
    }
    String instanceEvent = TaskOutcomeStatePolicy.resolveInstanceEvent(
        successCount,
        failedCount,
        allPartitionsFinished,
        dagContinues,
        TaskOutcomeStatePolicy.isDryRun(
            EmptyChecks.isNotNull(freshInstance) ? freshInstance : jobInstance));
    String instanceStatus = collaborators
        .stateMachine()
        .transition(
            EmptyChecks.isNotNull(freshInstance) ? freshInstance : jobInstance, instanceEvent)
        .toState();
    if (TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
        EmptyChecks.isNotNull(freshInstance)
            ? freshInstance.getInstanceStatus()
            : jobInstance.getInstanceStatus(),
        instanceEvent,
        successCount,
        failedCount,
        allPartitionsFinished,
        dagContinues)) {
      // 重试/补偿已完成全部分区时，旧失败终态只允许在实时统计证明全成功后收敛。
      instanceStatus = instanceEvent;
      log.info(
          "promoting stale failed job instance to success after all partitions completed:"
              + " tenantId={} jobInstanceId={} previousStatus={} successPartitions={}",
          command.tenantId(),
          jobInstance.getId(),
          EmptyChecks.isNotNull(freshInstance)
              ? freshInstance.getInstanceStatus()
              : jobInstance.getInstanceStatus(),
          successCount);
    }
    String instanceFailureClass = TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(instanceStatus)
            && (JobInstanceStatus.FAILED.code().equals(instanceStatus)
                || JobInstanceStatus.PARTIAL_FAILED.code().equals(instanceStatus))
        ? collaborators
            .failureClassifier()
            .classify(command.failureClass(), null)
            .code()
        : null;
    int progressUpdated =
        jobMappers.jobInstanceMapper.updateProgress(UpdateInstanceProgressParam.builder()
            .tenantId(command.tenantId())
            .id(jobInstance.getId())
            .instanceStatus(instanceStatus)
            .successPartitionCount((int) successCount)
            .failedPartitionCount((int) failedCount)
            .resultSummary(TaskOutcomeSummaryBuilder.buildJobInstanceResultSummary(
                jobInstance,
                successCount,
                TaskOutcomeSummaryBuilder.countBroadFailed(statusRefs),
                command))
            .finishedAt(jobFullyComplete ? finishedAt : null)
            .failureClass(instanceFailureClass)
            .expectedVersion(jobInstance.getVersion())
            .build());
    if (progressUpdated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict");
    }
    jobInstance.setVersion(Optional.ofNullable(jobInstance.getVersion()).orElse(0L) + 1);
    jobInstance.setInstanceStatus(instanceStatus);
    if (TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(instanceStatus)) {
      terminalFinalizer.finalizeTerminal(
          jobInstance,
          command,
          instanceStatus,
          jobFullyComplete ? finishedAt : null,
          instanceFailureClass,
          loadPartitions(command, task));
    }
    if (jobFullyComplete && TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(instanceStatus)) {
      parentTaskSignaler
          .buildParentOutcome(jobInstance, instanceStatus, command)
          .ifPresent(parentOutcomeApplier);
    }
    if (EmptyChecks.isNotNull(workflowRun)) {
      workflowFinalizer.finalizeWorkflow(TaskOutcomeWorkflowFinalizer.Context.builder()
          .command(command)
          .workflowRun(workflowRun)
          .failedPartitionCount(failedCount)
          .allPartitionsFinished(allPartitionsFinished)
          .dagContinues(dagContinues)
          .jobFullyComplete(jobFullyComplete)
          .activeNodes(activeNodes)
          .currentNodeCode(currentNodeCode)
          .finishedAt(finishedAt)
          .build());
    }
  }

  private String resolveCurrentNodeCode(JobTaskEntity task, WorkflowRunEntity workflowRun) {
    String nodeCode = TaskOutcomePayloadSupport.payloadStringValue(
        EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "workflowNodeCode");
    if (EmptyChecks.isNotBlank(nodeCode)) {
      return nodeCode;
    }
    Set<String> activeNodes = EmptyChecks.isNull(workflowRun)
        ? Set.of()
        : TaskOutcomeStatePolicy.parseActiveNodes(workflowRun.getCurrentNodeCode());
    if (EmptyChecks.isNotNull(workflowRun) && activeNodes.size() > 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.workflow.task_payload_missing_node_code",
          String.valueOf(EmptyChecks.isNull(task) ? null : task.getId()));
    }
    if (EmptyChecks.isNotEmpty(activeNodes)) {
      return activeNodes.iterator().next();
    }
    return WorkflowNodeCode.START.code();
  }

  private List<JobPartitionEntity> loadPartitions(TaskOutcomeCommand command, JobTaskEntity task) {
    return jobMappers.jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(command.tenantId(), task.getJobInstanceId(), null, null));
  }
}
