package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.domain.query.NodePartitionAssignment;
import io.github.pinpols.batch.orchestrator.domain.query.PartitionStatusRef;
import io.github.pinpols.batch.orchestrator.domain.statemachine.LifecycleEventMapper;
import io.github.pinpols.batch.orchestrator.domain.view.PartitionStatusSummary;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.failure.FailureClassifier;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 负责 worker 回报后的分区、实例和 workflow 聚合推进。
 *
 * <p>task 终态写入以后，分区状态、实例进度、节点切换、父任务回报和 workflow 终态必须按固定顺序
 * 在同一 report 事务中完成。将这段聚合编排从入口服务下沉后，入口只保留 report 的身份校验、task
 * CAS 和 step 镜像更新；本类仍不声明独立事务，避免把一次回报拆成多个提交单元。调用方必须在取得
 * instance advisory lock 后加载并传入权威实例快照，本类不再重复读取同一实例。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class TaskOutcomeInstanceProgressor implements TimedOutWorkflowNodeProgression {

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final LifecycleEventMapper<Object> lifecycleEventMapper;
  private final FailureClassifier failureClassifier;
  private final TaskOutcomeTerminalFinalizer terminalFinalizer;
  private final TaskOutcomeDagProgressor dagProgressor;
  private final TaskOutcomeWorkflowFinalizer workflowFinalizer;

  void advance(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      Instant finishedAt) {
    WorkflowRunEntity workflowRun = workflowMappers.workflowRunMapper.selectByNodeJobInstanceId(
        command.tenantId(), jobInstance.getId());
    boolean dagInstance = EmptyChecks.isNotNull(workflowRun);
    List<PartitionStatusRef> statusRefs = dagInstance
        ? jobMappers.jobPartitionMapper.selectStatusRefsByInstance(
            command.tenantId(), task.getJobInstanceId())
        : List.of();
    long totalPartitionCount = dagInstance
        ? statusRefs.size()
        : Optional.ofNullable(jobInstance.getExpectedPartitionCount()).orElse(0);
    long successCount = dagInstance
        ? statusRefs.stream()
            .filter(r -> PartitionStatus.SUCCESS.code().equals(r.partitionStatus()))
            .count()
        : Optional.ofNullable(jobInstance.getSuccessPartitionCount()).orElse(0);
    long failedCount = dagInstance
        ? statusRefs.stream()
            .filter(r -> PartitionStatus.FAILED.code().equals(r.partitionStatus()))
            .count()
        : Optional.ofNullable(jobInstance.getFailedPartitionCount()).orElse(0);
    long cancelledCount = dagInstance
        ? statusRefs.stream()
            .filter(r -> PartitionStatus.CANCELLED.code().equals(r.partitionStatus()))
            .count()
        : Optional.ofNullable(jobInstance.getCancelledPartitionCount()).orElse(0);
    long terminatedCount = dagInstance
        ? statusRefs.stream()
            .filter(r -> PartitionStatus.TERMINATED.code().equals(r.partitionStatus()))
            .count()
        : Optional.ofNullable(jobInstance.getTerminatedPartitionCount()).orElse(0);
    long broadFailedCount = failedCount + cancelledCount + terminatedCount;
    long finishedPartitionCount = successCount + broadFailedCount;
    boolean allPartitionsFinished =
        totalPartitionCount > 0 && finishedPartitionCount == totalPartitionCount;
    PartitionStatusSummary terminalVerification = null;
    if (!dagInstance && allPartitionsFinished) {
      terminalVerification = jobMappers.jobPartitionMapper.selectStatusSummaryByInstance(
          command.tenantId(), task.getJobInstanceId());
      boolean terminalPartitionSummaryIsInconsistent = EmptyChecks.isNull(terminalVerification)
          || terminalVerification.totalCount() != totalPartitionCount
          || terminalVerification.successCount() != successCount
          || terminalVerification.failedCount() != failedCount
          || terminalVerification.cancelledCount() != cancelledCount
          || terminalVerification.terminatedCount() != terminatedCount
          || terminalVerification.broadFailedCount() != broadFailedCount;
      if (terminalPartitionSummaryIsInconsistent) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict");
      }
    }
    String currentNodeCode = resolveCurrentNodeCode(task, workflowRun);
    List<NodePartitionAssignment> nodeAssignments = dagInstance
        ? jobMappers.jobTaskMapper.selectNodeAssignmentsByInstance(
            command.tenantId(), task.getJobInstanceId())
        : List.of();
    NodePartitionProgressCalculator.Result nodeProgress = NodePartitionProgressCalculator.calculate(
        statusRefs, nodeAssignments, currentNodeCode, workflowRun);
    Set<String> activeNodes = EmptyChecks.isNull(workflowRun)
        ? new LinkedHashSet<>()
        : TaskOutcomeStatePolicy.parseActiveNodes(workflowRun.getCurrentNodeCode());

    if (nodeProgress.allFinished() && EmptyChecks.isNotNull(workflowRun)) {
      List<JobPartitionEntity> nodeCompletionPartitions = loadPartitions(command, task);
      List<JobPartitionEntity> successfulNodePartitions =
          TaskOutcomeSummaryBuilder.filterPartitionsByIds(
              nodeCompletionPartitions, nodeProgress.partitionIds());
      Map<String, Object> nodeOutputs =
          TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(
              successfulNodePartitions, command);
      TaskOutcomeDagProgressor.Context advanceContext = TaskOutcomeDagProgressor.Context.builder()
          .command(command)
          .task(task)
          .jobInstance(jobInstance)
          .workflowRun(workflowRun)
          .currentNodeCode(currentNodeCode)
          .nodeProgress(nodeProgress)
          .nodeOutputs(nodeOutputs)
          .activeNodes(activeNodes)
          .finishedAt(finishedAt)
          .build();
      dagProgressor.advance(advanceContext);
      // 派发期游标可能残留已完成的 FORK；以最新 node_run 重建活跃集合，避免 END 成功后实例永久 RUNNING。
      activeNodes.clear();
      List<WorkflowNodeRunEntity> nodeRuns =
          workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId());
      Set<String> resolvedActiveNodes = TaskOutcomeStatePolicy.resolveActiveNodeCodes(nodeRuns);
      activeNodes.addAll(resolvedActiveNodes);
    }

    boolean dagContinues =
        EmptyChecks.isNotNull(workflowRun) && EmptyChecks.isNotEmpty(activeNodes);
    // 节点 JobInstance 只拥有自己的分区。兄弟/后代 Workflow 节点会让
    // WorkflowRun 保持活动，但不能让此已完成的 JobInstance 继续占用准入名额。
    boolean jobFullyComplete = allPartitionsFinished;
    String instanceEvent = TaskOutcomeStatePolicy.resolveInstanceEvent(
        successCount,
        failedCount,
        cancelledCount,
        terminatedCount,
        allPartitionsFinished,
        TaskOutcomeStatePolicy.isDryRun(jobInstance));
    String instanceStatus = lifecycleEventMapper.map(jobInstance, instanceEvent).toState();
    if (TaskOutcomeStatePolicy.shouldPromoteTerminalFailure(
        jobInstance.getInstanceStatus(),
        instanceEvent,
        successCount,
        failedCount,
        allPartitionsFinished)) {
      // 重试/补偿已完成全部分区时，旧失败终态只允许在实时统计证明全成功后收敛。
      instanceStatus = instanceEvent;
      String tenantIdForLog = LogSanitizer.value(command.tenantId());
      Long jobInstanceIdForLog = jobInstance.getId();
      String previousStatusForLog = LogSanitizer.value(jobInstance.getInstanceStatus());
      log.info(
          "promoting stale failed job instance to success after all partitions completed:"
              + " tenantId={} jobInstanceId={} previousStatus={} successPartitions={}",
          tenantIdForLog,
          jobInstanceIdForLog,
          previousStatusForLog,
          successCount);
    }
    String instanceFailureClass = TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(instanceStatus)
            && (JobInstanceStatus.FAILED.code().equals(instanceStatus)
                || JobInstanceStatus.PARTIAL_FAILED.code().equals(instanceStatus))
        ? failureClassifier.classify(command.failureClass(), null).code()
        : null;
    JobInstanceMapper progressMapper = jobMappers.jobInstanceMapper;
    UpdateInstanceProgressParam.UpdateInstanceProgressParamBuilder progressBuilder =
        UpdateInstanceProgressParam.builder()
            .tenantId(command.tenantId())
            .id(jobInstance.getId())
            .instanceStatus(instanceStatus)
            .successPartitionCount((int) successCount)
            .failedPartitionCount((int) failedCount)
            .cancelledPartitionCount((int) cancelledCount)
            .terminatedPartitionCount((int) terminatedCount);
    String resultSummary = TaskOutcomeSummaryBuilder.buildJobInstanceResultSummary(
        jobInstance, successCount, broadFailedCount, command);
    UpdateInstanceProgressParam progress = progressBuilder
        .resultSummary(resultSummary)
        .finishedAt(jobFullyComplete ? finishedAt : null)
        .failureClass(instanceFailureClass)
        .expectedVersion(jobInstance.getVersion())
        .build();
    int progressUpdated = progressMapper.updateProgress(progress);
    if (progressUpdated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict");
    }
    Long currentVersion = Optional.ofNullable(jobInstance.getVersion()).orElse(0L);
    Long nextVersion = currentVersion + 1;
    jobInstance.setVersion(nextVersion);
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
    if (EmptyChecks.isNotNull(workflowRun)) {
      workflowFinalizer.finalizeWorkflow(TaskOutcomeWorkflowFinalizer.Context.builder()
          .command(command)
          .workflowRun(workflowRun)
          .failedPartitionCount(broadFailedCount)
          .allPartitionsFinished(allPartitionsFinished)
          .dagContinues(dagContinues)
          .jobFullyComplete(jobFullyComplete)
          .activeNodes(activeNodes)
          .currentNodeCode(currentNodeCode)
          .finishedAt(finishedAt)
          .build());
    }
  }

  /**
   * 硬超时路径已将所有活动子任务和分区置为终态后，重新协调 Workflow 节点。
   * 不同于 Worker REPORT，此处不得再次改写
   * JobInstance；只复用标准 DAG 失败传播和 Workflow 收尾逻辑。
   * 终态收敛规则。
   */
  @Override
  public void advanceTimedOutWorkflowNode(String tenantId, Long jobInstanceId, Instant finishedAt) {
    JobInstanceEntity jobInstance =
        jobMappers.jobInstanceMapper.selectById(tenantId, jobInstanceId);
    if (EmptyChecks.isNull(jobInstance)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict");
    }
    WorkflowRunEntity workflowRun =
        workflowMappers.workflowRunMapper.selectByNodeJobInstanceId(tenantId, jobInstanceId);
    if (EmptyChecks.isNull(workflowRun)) {
      return;
    }
    WorkflowNodeRunEntity linkedNode =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId()).stream()
            .filter(node -> jobInstanceId.equals(node.getJobInstanceId()))
            .max(Comparator.comparing(
                node -> Optional.ofNullable(node.getRunSeq()).orElse(0)))
            .orElse(null);
    boolean linkedNodeIsMissingOrTerminal = EmptyChecks.isNull(linkedNode)
        || WorkflowNodeRunStatus.FAILED.code().equals(linkedNode.getNodeStatus())
        || WorkflowNodeRunStatus.SUCCESS.code().equals(linkedNode.getNodeStatus())
        || WorkflowNodeRunStatus.SKIPPED.code().equals(linkedNode.getNodeStatus());
    if (linkedNodeIsMissingOrTerminal) {
      return;
    }
    JobTaskQuery instanceTaskQuery = JobTaskQuery.ofJobInstance(tenantId, jobInstanceId);
    List<JobTaskEntity> instanceTasks = jobMappers.jobTaskMapper.selectByQuery(instanceTaskQuery);
    JobTaskEntity task = instanceTasks.stream()
        .filter(candidate -> {
          String linkedNodeCode = linkedNode.getNodeCode();
          String candidateNodeCode = TaskOutcomePayloadSupport.payloadStringValue(
              candidate.getTaskPayload(), "workflowNodeCode");
          return linkedNodeCode.equals(candidateNodeCode);
        })
        .findFirst()
        .orElseThrow(() ->
            BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict"));
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId(tenantId)
        .taskId(task.getId())
        .success(false)
        .errorCode("JOB_INSTANCE_TIMEOUT")
        .errorMessage("Job instance exceeded its configured timeout")
        .build();
    List<PartitionStatusRef> statusRefs =
        jobMappers.jobPartitionMapper.selectStatusRefsByInstance(tenantId, jobInstanceId);
    List<NodePartitionAssignment> nodeAssignments =
        jobMappers.jobTaskMapper.selectNodeAssignmentsByInstance(tenantId, jobInstanceId);
    NodePartitionProgressCalculator.Result nodeProgress = NodePartitionProgressCalculator.calculate(
        statusRefs, nodeAssignments, linkedNode.getNodeCode(), workflowRun);
    if (!nodeProgress.allFinished()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict");
    }
    Set<String> activeNodes =
        TaskOutcomeStatePolicy.parseActiveNodes(workflowRun.getCurrentNodeCode());
    JobPartitionQuery instancePartitionQuery =
        JobPartitionQuery.ofJobInstance(tenantId, jobInstanceId);
    List<JobPartitionEntity> partitions =
        jobMappers.jobPartitionMapper.selectByQuery(instancePartitionQuery);
    Set<Long> partitionIds = nodeProgress.partitionIds();
    List<JobPartitionEntity> nodePartitions =
        TaskOutcomeSummaryBuilder.filterPartitionsByIds(partitions, partitionIds);
    Map<String, Object> nodeOutputs =
        TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(nodePartitions, command);
    dagProgressor.advance(TaskOutcomeDagProgressor.Context.builder()
        .command(command)
        .task(task)
        .jobInstance(jobInstance)
        .workflowRun(workflowRun)
        .currentNodeCode(linkedNode.getNodeCode())
        .nodeProgress(nodeProgress)
        .nodeOutputs(nodeOutputs)
        .activeNodes(activeNodes)
        .finishedAt(finishedAt)
        .build());
    activeNodes.clear();
    Long workflowRunId = workflowRun.getId();
    List<WorkflowNodeRunEntity> activeNodeRuns =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRunId);
    Set<String> activeNodeCodes = TaskOutcomeStatePolicy.resolveActiveNodeCodes(activeNodeRuns);
    activeNodes.addAll(activeNodeCodes);
    long unsuccessfulCount = nodeProgress.unsuccessfulCount();
    workflowFinalizer.finalizeWorkflow(TaskOutcomeWorkflowFinalizer.Context.builder()
        .command(command)
        .workflowRun(workflowRun)
        .failedPartitionCount(unsuccessfulCount)
        .allPartitionsFinished(true)
        .dagContinues(!EmptyChecks.isEmpty(Objects.requireNonNull(activeNodes)))
        .jobFullyComplete(true)
        .activeNodes(activeNodes)
        .currentNodeCode(linkedNode.getNodeCode())
        .finishedAt(finishedAt)
        .build());
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
    JobPartitionQuery query =
        JobPartitionQuery.ofJobInstance(command.tenantId(), task.getJobInstanceId());
    return jobMappers.jobPartitionMapper.selectByQuery(query);
  }
}
