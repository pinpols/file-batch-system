package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.FinishTaskParam;
import com.example.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import com.example.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.domain.param.UpdateStepProgressParam;
import com.example.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * worker 回报（report）后的"状态推进中枢"。
 *
 * <p>在本系统中，worker 不直接修改 orchestrator 的运行态表，而是通过 HTTP {@code /internal/tasks/{taskId}/report}
 * 回报执行结果；orchestrator 在这里统一完成：
 *
 * <ul>
 *   <li>写入 task 的终态（SUCCESS/FAILED）
 *   <li>根据失败决定是否进入重试（写 retry_schedule，并把 partition/task/step 标记为 RETRYING）
 *   <li>推进 partition/job_instance/workflow_run 的状态机（含 DAG 节点切换与下一节点派发）
 *   <li>更新 step 镜像 {@code job_step_instance}（用于审计/可视化口径一致）
 * </ul>
 *
 * <p>重要约束：
 *
 * <ul>
 *   <li>只接受 RUNNING 状态的 task 回报，避免重复 report 导致状态回跳。
 *   <li>并发冲突靠 DB 乐观锁/条件更新兜底（更新行数为 0 → STATE_CONFLICT）。
 * </ul>
 */
@Service
@Slf4j
public class DefaultTaskOutcomeService implements TaskOutcomeService {

  /**
   * workflow_run.updateStatus 期望前态白名单：仅 CREATED / RUNNING 才允许 outcome 推动状态机； cancel/terminate 已把
   * run 切到 TERMINATED 后再来 outcome 应当被守护拦掉。
   */
  private static final List<String> WORKFLOW_RUN_LIVE_STATUSES =
      List.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code());

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final RetryGovernanceService retryGovernanceService;
  private final StateMachine<Object> stateMachine;
  private final WorkflowDagService workflowDagService;
  private final ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider;
  private final WorkflowTerminalOutboxService workflowTerminalOutboxService;
  // #1-2: CAS 冲突计数器，用于监控并发更新频率
  private final Counter casMissCounter;

  @Lazy @Autowired private DefaultTaskOutcomeService self;

  public DefaultTaskOutcomeService(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      RetryGovernanceService retryGovernanceService,
      StateMachine<Object> stateMachine,
      WorkflowDagService workflowDagService,
      ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider,
      WorkflowTerminalOutboxService workflowTerminalOutboxService,
      MeterRegistry meterRegistry) {
    this.jobMappers = jobMappers;
    this.workflowMappers = workflowMappers;
    this.retryGovernanceService = retryGovernanceService;
    this.stateMachine = stateMachine;
    this.workflowDagService = workflowDagService;
    this.workflowNodeDispatchServiceProvider = workflowNodeDispatchServiceProvider;
    this.workflowTerminalOutboxService = workflowTerminalOutboxService;
    this.casMissCounter =
        Counter.builder("batch.orchestrator.cas.miss")
            .description("CAS miss count during optimistic locking updates")
            .register(meterRegistry);
  }

  // #8-3: 启动时验证 ObjectProvider 可正常解析，将循环依赖暴露在启动阶段而非运行时
  @PostConstruct
  void verifyLazyDependencies() {
    try {
      workflowNodeDispatchServiceProvider.getIfAvailable();
    } catch (Exception ex) {
      log.error("WorkflowNodeDispatchService 延迟注入解析失败，可能存在循环依赖: {}", ex.getMessage());
      throw new IllegalStateException(
          "WorkflowNodeDispatchService ObjectProvider 解析失败，请检查循环依赖", ex);
    }
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunReady(
      Long workflowRunId, String nodeCode, String nodeType) {
    WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
    entity.setWorkflowRunId(workflowRunId);
    entity.setNodeCode(nodeCode);
    entity.setNodeType(nodeType);
    entity.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
    entity.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    entity.setRetryCount(0);
    entity.setDurationMs(0L);
    // #3-2: 并发安全——唯一约束冲突时返回已有记录而非报错
    try {
      workflowMappers.workflowNodeRunMapper.insert(entity);
    } catch (DuplicateKeyException ignored) {
      return workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
          workflowRunId, nodeCode);
    }
    return entity;
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunStart(
      Long workflowRunId, String nodeCode, String nodeType, Instant startedAt) {
    WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
    entity.setWorkflowRunId(workflowRunId);
    entity.setNodeCode(nodeCode);
    entity.setNodeType(nodeType);
    entity.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
    entity.setNodeStatus(WorkflowNodeRunStatus.RUNNING.code());
    entity.setRetryCount(0);
    entity.setStartedAt(startedAt);
    entity.setDurationMs(0L);
    // #3-2: 并发安全——唯一约束冲突时返回已有记录而非报错
    try {
      workflowMappers.workflowNodeRunMapper.insert(entity);
    } catch (DuplicateKeyException ignored) {
      return workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
          workflowRunId, nodeCode);
    }
    return entity;
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunFinish(NodeRunFinishCommand command) {
    // C-1: 行锁防止并发 recordNodeRunFinish 对同一 (workflowRunId, nodeCode) 创建重复 node_run 记录
    WorkflowNodeRunEntity current =
        workflowMappers.workflowNodeRunMapper.selectLatestForUpdate(
            command.workflowRunId(), command.nodeCode());
    if (current == null) {
      current =
          self.recordNodeRunStart(
              command.workflowRunId(), command.nodeCode(), command.nodeType(), command.startedAt());
    }
    long duration =
        command.startedAt() == null || command.finishedAt() == null
            ? 0L
            : Duration.between(command.startedAt(), command.finishedAt()).toMillis();
    workflowMappers.workflowNodeRunMapper.updateStatus(
        UpdateNodeRunStatusParam.builder()
            .id(current.getId())
            .nodeStatus(
                command.success()
                    ? WorkflowNodeRunStatus.SUCCESS.code()
                    : WorkflowNodeRunStatus.FAILED.code())
            .errorCode(command.errorCode())
            .errorMessage(command.errorMessage())
            .errorKey(command.errorKey())
            .errorArgs(command.errorArgs())
            .durationMs(duration)
            .finishedAt(command.finishedAt())
            // ADR-009 Stage 1.2: success 路径写 worker 上报的 outputs JSON,失败路径保持 null。
            .output(command.success() ? command.outputJson() : null)
            .build());
    return workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
        command.workflowRunId(), command.nodeCode());
  }

  @Override
  @Transactional
  public JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command) {
    // 入口语义：worker report → orchestrator 在单事务内完成"任务完成 +（可选）重试入队 + 状态机推进"。
    if (command == null) {
      return null;
    }
    JobTaskEntity task = jobMappers.jobTaskMapper.selectById(command.tenantId(), command.taskId());
    if (task == null) {
      return null;
    }
    // 只处理 RUNNING → terminal 的一次性回报；重复回报直接返回当前状态，保证幂等。
    if (!TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
      log.info(
          "task outcome ignored (already {}): taskId={}", task.getTaskStatus(), command.taskId());
      return task;
    }
    // workerId 非空时校验 worker 归属，防止恶意/错误 worker 伪造回报。
    if (command.workerId() != null && !command.workerId().equals(task.getAssignedWorkerCode())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.worker.not_owner");
    }
    Instant finishedAt = Instant.now();
    JobPartitionEntity partition =
        jobMappers.jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId());
    if (task.getJobPartitionId() != null && Texts.hasText(command.partitionInvocationId())) {
      if (partition == null
          || partition.getCurrentInvocationId() == null
          || !partition.getCurrentInvocationId().equals(command.partitionInvocationId())) {
        throw BizException.of(ResultCode.CONFLICT, "error.task.invocation_mismatch");
      }
    }
    JobInstanceEntity jobInstance =
        jobMappers.jobInstanceMapper.selectById(command.tenantId(), task.getJobInstanceId());
    // 失败时是否进入重试：由治理层统一决策（NONE/预算耗尽 → dead-letter；否则写 retry_schedule）。
    boolean retryScheduled =
        !command.success()
            && partition != null
            && jobInstance != null
            && retryGovernanceService.scheduleRetryIfNecessary(
                task, partition, jobInstance, command.errorCode(), command.errorMessage());
    int updated =
        jobMappers.jobTaskMapper.finishTask(
            FinishTaskParam.builder()
                .tenantId(command.tenantId())
                .id(command.taskId())
                .taskStatus(
                    command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code())
                .expectedStatus(TaskStatus.RUNNING.code())
                .resultSummary(command.resultSummary())
                .errorCode(command.errorCode())
                .errorMessage(command.errorMessage())
                .errorKey(command.errorKey())
                .errorArgs(command.errorArgs())
                .expectedVersion(task.getVersion())
                .build());
    if (updated <= 0) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "task already finished by concurrent update: taskId=" + command.taskId());
    }

    if (command.success()) {
      applySuccessOutcome(command, partition);
      // ExecutionMode.INCREMENTAL:把 worker 上报的新水位回写到 job_instance。null/空跳过
      // (保留旧值,下次启动时同 IN 不变);仅成功路径推水位,失败/重试不应推进。
      if (command.highWaterMarkOut() != null && !command.highWaterMarkOut().isBlank()) {
        int wmUpdated =
            jobMappers.jobInstanceMapper.updateHighWaterMarkOut(
                command.tenantId(), task.getJobInstanceId(), command.highWaterMarkOut());
        if (wmUpdated <= 0) {
          // CAS 守护拦下:更高水位已就绪 (并发 partition 回报乱序) 或新值格式非法,debug 即可。
          log.debug(
              "high_water_mark_out CAS no-op for jobInstance {}: incoming={} (regression or"
                  + " malformed)",
              task.getJobInstanceId(),
              command.highWaterMarkOut());
        }
      }
    } else {
      applyFailureOutcome(command, partition, retryScheduled);
    }
    if (partition != null) {
      jobMappers.jobPartitionMapper.updateOutputSummary(
          command.tenantId(),
          partition.getId(),
          TaskOutcomeSummaryBuilder.buildOutputSummary(command, task));
    }
    // step 镜像用于"按 step 维度"看执行状态/重试次数，与 task/partition 状态保持一致口径。
    updateStepInstanceProgress(command, task, retryScheduled, finishedAt);
    if (jobInstance != null) {
      advancePartitionAndInstance(command, task, jobInstance, finishedAt);
    }
    return jobMappers.jobTaskMapper.selectById(command.tenantId(), command.taskId());
  }

  private void warnIfCasMiss(int updated, String context, long partitionId) {
    if (updated <= 0) {
      // #1-2: 指标上报，方便监控告警
      casMissCounter.increment();
      log.warn(
          "{} CAS miss - concurrent update likely already advanced: partitionId={}",
          context,
          partitionId);
    }
  }

  /** 处理成功路径：将分区标记为 SUCCESS。 */
  private void applySuccessOutcome(TaskOutcomeCommand command, JobPartitionEntity partition) {
    if (partition == null) {
      return;
    }
    // C-8: 检查 markStatus 返回值，0 行表示并发更新已推进分区状态，保证分区与任务状态在同一事务内一致
    int partitionUpdated =
        jobMappers.jobPartitionMapper.markStatus(
            MarkPartitionStatusParam.builder()
                .tenantId(command.tenantId())
                .id(partition.getId())
                .partitionStatus(PartitionStatus.SUCCESS.code())
                .runningStatus(PartitionStatus.RUNNING.code())
                .terminalStatus1(PartitionStatus.SUCCESS.code())
                .terminalStatus2(PartitionStatus.FAILED.code())
                .terminalStatus3(PartitionStatus.CANCELLED.code())
                .terminalStatus4(PartitionStatus.TERMINATED.code())
                .expectedVersion(partition.getVersion())
                .build());
    warnIfCasMiss(partitionUpdated, "partition markStatus(SUCCESS)", partition.getId());
  }

  /** 处理失败/重试路径：根据是否安排重试，将分区标记为 RETRYING 或 FAILED。 */
  private void applyFailureOutcome(
      TaskOutcomeCommand command, JobPartitionEntity partition, boolean retryScheduled) {
    if (partition == null) {
      return;
    }
    if (retryScheduled) {
      // 进入 RETRYING：partition 先标记为 RETRYING，实际重排队由 retry scheduler → outbox 完成。
      int retryUpdated =
          jobMappers.jobPartitionMapper.markRetrying(
              command.tenantId(),
              partition.getId(),
              Optional.ofNullable(partition.getRetryCount()).orElse(0) + 1,
              PartitionStatus.RETRYING.code(),
              partition.getVersion());
      warnIfCasMiss(retryUpdated, "partition markRetrying", partition.getId());
    } else {
      int failUpdated =
          jobMappers.jobPartitionMapper.markStatus(
              MarkPartitionStatusParam.builder()
                  .tenantId(command.tenantId())
                  .id(partition.getId())
                  .partitionStatus(PartitionStatus.FAILED.code())
                  .runningStatus(PartitionStatus.RUNNING.code())
                  .terminalStatus1(PartitionStatus.SUCCESS.code())
                  .terminalStatus2(PartitionStatus.FAILED.code())
                  .terminalStatus3(PartitionStatus.CANCELLED.code())
                  .terminalStatus4(PartitionStatus.TERMINATED.code())
                  .expectedVersion(partition.getVersion())
                  .build());
      warnIfCasMiss(failUpdated, "partition markStatus(FAILED)", partition.getId());
    }
  }

  /** 推进分区/实例状态机：统计分区完成情况，更新 job_instance 状态，处理 DAG 节点流转。 */
  private void advancePartitionAndInstance(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      Instant finishedAt) {
    // C-2: 行锁序列化并发 outcome 处理器的分区计数，防止读到过期计数导致状态机转换冲突
    List<JobPartitionEntity> partitions =
        jobMappers.jobPartitionMapper.selectByQueryForUpdate(
            new JobPartitionQuery(command.tenantId(), task.getJobInstanceId(), null, null));
    long successCount =
        partitions.stream()
            .filter(p -> PartitionStatus.SUCCESS.code().equals(p.getPartitionStatus()))
            .count();
    long failedCount =
        partitions.stream()
            .filter(p -> PartitionStatus.FAILED.code().equals(p.getPartitionStatus()))
            .count();
    long finishedPartitionCount = successCount + failedCount;
    boolean allPartitionsFinished =
        !partitions.isEmpty() && finishedPartitionCount == partitions.size();
    WorkflowRunEntity workflowRun =
        workflowMappers.workflowRunMapper.selectByRelatedJobInstanceId(
            command.tenantId(), jobInstance.getId());
    String currentNodeCode = resolveCurrentNodeCode(task, workflowRun);
    List<JobTaskEntity> tasks =
        jobMappers.jobTaskMapper.selectByQuery(
            new JobTaskQuery(command.tenantId(), task.getJobInstanceId(), null, null, null));
    NodePartitionProgress nodeProgress =
        resolveNodePartitionProgress(partitions, tasks, currentNodeCode, workflowRun);
    Set<String> activeNodes =
        workflowRun == null
            ? new LinkedHashSet<>()
            : parseActiveNodes(workflowRun.getCurrentNodeCode());

    if (nodeProgress.allFinished() && workflowRun != null) {
      DagAdvanceContext advanceCtx =
          DagAdvanceContext.builder()
              .command(command)
              .task(task)
              .jobInstance(jobInstance)
              .workflowRun(workflowRun)
              .currentNodeCode(currentNodeCode)
              .nodeProgress(nodeProgress)
              .activeNodes(activeNodes)
              .finishedAt(finishedAt)
              .build();
      advanceDagNodes(advanceCtx);
    }

    boolean dagContinues = workflowRun != null && !activeNodes.isEmpty();
    boolean jobFullyComplete = allPartitionsFinished && !dagContinues;
    // #3-1: 重新读取 instance 获取最新 version，避免并发 outcome 间版本冲突导致永久循环。
    // 此时分区行已被 FOR UPDATE 锁住，保证了分区计数的串行性，
    // 但 job_instance 本身可能被其他已完成的 outcome 更新了 version。
    // C-2.2: 重新读取 instance 获取最新 version 和状态，直接用 freshInstance 做状态机转换，
    // 避免 jobInstance 上残留过期字段导致 stateMachine 基于错误状态计算转换结果
    JobInstanceEntity freshInstance =
        jobMappers.jobInstanceMapper.selectById(command.tenantId(), jobInstance.getId());
    if (freshInstance != null) {
      jobInstance.setVersion(freshInstance.getVersion());
      jobInstance.setInstanceStatus(freshInstance.getInstanceStatus());
    }
    String instanceEvent =
        resolveInstanceEvent(successCount, failedCount, allPartitionsFinished, dagContinues);
    String instanceStatus =
        stateMachine
            .transition(freshInstance != null ? freshInstance : jobInstance, instanceEvent)
            .toState();
    int progressUpdated =
        jobMappers.jobInstanceMapper.updateProgress(
            UpdateInstanceProgressParam.builder()
                .tenantId(command.tenantId())
                .id(jobInstance.getId())
                .instanceStatus(instanceStatus)
                .successPartitionCount((int) successCount)
                .failedPartitionCount((int) failedCount)
                .resultSummary(
                    TaskOutcomeSummaryBuilder.buildJobInstanceResultSummary(
                        jobInstance, partitions, command))
                .finishedAt(jobFullyComplete ? finishedAt : null)
                .expectedVersion(jobInstance.getVersion())
                .build());
    if (progressUpdated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.instance_progress_conflict");
    }
    jobInstance.setVersion(Optional.ofNullable(jobInstance.getVersion()).orElse(0L) + 1);
    // 若本作业由 DAG 中 JOB 节点子作业拉起，需回写父侧信号
    if (jobFullyComplete && isTerminalJobInstanceStatus(instanceStatus)) {
      signalParentVirtualTask(jobInstance, instanceStatus, command);
    }
    if (workflowRun != null) {
      String workflowEvent = resolveWorkflowEvent(failedCount, allPartitionsFinished, dagContinues);
      String workflowStatus = stateMachine.transition(workflowRun, workflowEvent).toState();
      Instant workflowFinishedAt = jobFullyComplete ? finishedAt : null;
      // C-2.3: SQL 守护期望前态 {CREATED, RUNNING}，避免与运维 cancel/terminate 抢占造成 TERMINATED 覆写
      int updated =
          workflowMappers.workflowRunMapper.updateStatus(
              UpdateWorkflowRunStatusParam.builder()
                  .tenantId(command.tenantId())
                  .id(workflowRun.getId())
                  .runStatus(workflowStatus)
                  .currentNodeCode(
                      resolveWorkflowCurrentNode(activeNodes, workflowStatus, currentNodeCode))
                  .finishedAt(workflowFinishedAt)
                  .expectedStatuses(WORKFLOW_RUN_LIVE_STATUSES)
                  .build());
      if (updated <= 0) {
        log.warn(
            "workflow_run {} already in terminal state when outcome arrived; skip transition to"
                + " {} (likely cancel/terminate raced ahead)",
            workflowRun.getId(),
            workflowStatus);
      } else if (WorkflowTerminalOutboxService.isTerminal(workflowStatus)) {
        workflowTerminalOutboxService.writeTerminalEvent(
            workflowRun, workflowStatus, workflowFinishedAt);
      }
    }
  }

  /** DAG 工作流节点推进：完成当前节点运行记录，解析并派发后继节点。 */
  private void advanceDagNodes(DagAdvanceContext ctx) {
    ctx.activeNodes().remove(ctx.currentNodeCode());
    NodeRunOutcome currentOutcome =
        NodeRunOutcome.builder()
            .success(ctx.nodeProgress().failedCount() == 0)
            .errorCode(ctx.command().errorCode())
            .errorMessage(ctx.command().errorMessage())
            .errorKey(ctx.command().errorKey())
            .errorArgs(ctx.command().errorArgs())
            .startedAt(
                resolveNodeStartedAt(
                    ctx.workflowRun().getId(),
                    ctx.currentNodeCode(),
                    ctx.workflowRun().getStartedAt(),
                    ctx.finishedAt()))
            .finishedAt(ctx.finishedAt())
            .outputJson(TaskOutcomeSummaryBuilder.serializeOutputs(ctx.command().outputs()))
            .build();
    NodeRunKey currentKey =
        new NodeRunKey(
            ctx.workflowRun().getId(), ctx.currentNodeCode(), resolveCurrentNodeType(ctx.task()));
    self.recordNodeRunFinish(NodeRunFinishCommand.of(currentKey, currentOutcome));
    List<WorkflowDagService.DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodes(
            ctx.workflowRun().getWorkflowDefinitionId(),
            ctx.currentNodeCode(),
            ctx.nodeProgress().failedCount() == 0,
            ctx.task().getTaskPayload());
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (nextNode == null) {
        continue;
      }
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        if (workflowDagService.isNodeReadyForDispatch(
            ctx.workflowRun().getId(),
            ctx.workflowRun().getWorkflowDefinitionId(),
            nextNode.nodeCode(),
            ctx.task().getTaskPayload())) {
          self.recordNodeRunStart(
              ctx.workflowRun().getId(),
              nextNode.nodeCode(),
              nextNode.nodeType(),
              ctx.finishedAt());
          // END 节点没有 worker 上报，output 永远 null
          NodeRunOutcome endOutcome =
              NodeRunOutcome.builder()
                  .success(ctx.nodeProgress().failedCount() == 0)
                  .errorCode(ctx.command().errorCode())
                  .errorMessage(ctx.command().errorMessage())
                  .errorKey(ctx.command().errorKey())
                  .errorArgs(ctx.command().errorArgs())
                  .startedAt(ctx.finishedAt())
                  .finishedAt(ctx.finishedAt())
                  .build();
          NodeRunKey endKey =
              new NodeRunKey(ctx.workflowRun().getId(), nextNode.nodeCode(), nextNode.nodeType());
          self.recordNodeRunFinish(NodeRunFinishCommand.of(endKey, endOutcome));
        }
        continue;
      }
      workflowNodeDispatchServiceProvider
          .getObject()
          .dispatchNode(
              ctx.jobInstance(),
              ctx.workflowRun(),
              nextNode,
              ctx.task().getTaskPayload(),
              ctx.jobInstance().getTraceId());
      if (isActiveNode(ctx.workflowRun().getId(), nextNode.nodeCode())) {
        ctx.activeNodes().add(nextNode.nodeCode());
      }
    }
    // 当前节点 FAILED 时级联将永远无法触发的 SUCCESS-edge 下游写为 SKIPPED；防止 ALL-mode join 因
    // 缺失上游 node_run 行陷入永久死锁（terminalCount/matchedCount 永远到不了 size）。
    if (ctx.nodeProgress().failedCount() > 0) {
      workflowDagService.cascadeSkipDownstream(
          ctx.workflowRun().getId(),
          ctx.workflowRun().getWorkflowDefinitionId(),
          ctx.currentNodeCode());
    }
  }

  private void updateStepInstanceProgress(
      TaskOutcomeCommand command, JobTaskEntity task, boolean retryScheduled, Instant finishedAt) {
    if (command == null || task == null) {
      return;
    }
    JobStepInstanceEntity stepInstance =
        jobMappers.jobStepInstanceMapper.selectByJobTaskId(command.tenantId(), task.getId());
    if (stepInstance == null) {
      return;
    }
    String nextStatus =
        retryScheduled
            ? "RETRYING"
            : command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code();
    int currentRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0);
    int nextRetryCount = retryScheduled ? currentRetryCount + 1 : currentRetryCount;
    int updated =
        jobMappers.jobStepInstanceMapper.updateProgress(
            UpdateStepProgressParam.builder()
                .tenantId(command.tenantId())
                .id(stepInstance.getId())
                .stepStatus(nextStatus)
                .retryCount(nextRetryCount)
                .relatedFileId(TaskOutcomePayloadSupport.resolveRelatedFileId(task, command))
                .resultSummary(TaskOutcomeSummaryBuilder.buildOutputSummary(command, task))
                .errorCode(command.errorCode())
                .errorMessage(command.errorMessage())
                .errorKey(command.errorKey())
                .errorArgs(command.errorArgs())
                .finishedAt(retryScheduled ? null : finishedAt)
                .expectedVersion(stepInstance.getVersion())
                .build());
    if (updated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.step_progress_conflict");
    }
  }

  /** 当 JOB 节点启动的子 Job 到达终态时，将结果应用到父 Job 中的虚拟任务， 由标准的基于分区的 DAG 推进逻辑接管后续流转。 */
  private void signalParentVirtualTask(
      JobInstanceEntity childJobInstance,
      String childInstanceStatus,
      TaskOutcomeCommand childCommand) {
    Long parentVirtualTaskId = extractParentVirtualTaskId(childJobInstance.getParamsSnapshot());
    if (parentVirtualTaskId == null) {
      return;
    }
    boolean nodeSuccess = JobInstanceStatus.SUCCESS.code().equals(childInstanceStatus);
    // 父虚拟任务不直接推父水位：子作业自己的 outcome 已经写过对应实例的 high_water_mark_out；
    // 父侧不与子作业共享水位。ADR-009: JOB 节点把子作业的 outputs 透传到父 workflow 节点（供下游 DSL 引用）。
    TaskOutcomeCommand parentCommand =
        TaskOutcomeCommand.builder()
            .tenantId(childJobInstance.getTenantId())
            .taskId(parentVirtualTaskId)
            .success(nodeSuccess)
            .resultSummary(JsonUtils.toJson(Map.of("childInstanceStatus", childInstanceStatus)))
            .errorCode(nodeSuccess ? null : childCommand.errorCode())
            .errorMessage(nodeSuccess ? null : childCommand.errorMessage())
            .errorKey(nodeSuccess ? null : childCommand.errorKey())
            .errorArgs(nodeSuccess ? null : childCommand.errorArgs())
            .outputs(nodeSuccess ? childCommand.outputs() : null)
            .build();
    self.applyTaskOutcome(parentCommand);
  }

  @SuppressWarnings("unchecked")
  private Long extractParentVirtualTaskId(String paramsSnapshot) {
    if (paramsSnapshot == null || paramsSnapshot.isBlank()) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(paramsSnapshot, Object.class);
      if (!(parsed instanceof Map<?, ?> snapshotMap)) {
        return null;
      }
      Object effectiveParams = ((Map<String, Object>) snapshotMap).get("effectiveParams");
      if (!(effectiveParams instanceof Map<?, ?> effectiveMap)) {
        return null;
      }
      Object value = ((Map<String, Object>) effectiveMap).get("_parentVirtualTaskId");
      return TaskOutcomePayloadSupport.toPositiveLong(value);
    } catch (Exception ignored) {
      return null;
    }
  }

  private boolean isTerminalJobInstanceStatus(String status) {
    return JobInstanceStatus.SUCCESS.code().equals(status)
        || JobInstanceStatus.FAILED.code().equals(status)
        || JobInstanceStatus.PARTIAL_FAILED.code().equals(status)
        || JobInstanceStatus.CANCELLED.code().equals(status)
        || JobInstanceStatus.TERMINATED.code().equals(status);
  }

  private String resolveCurrentNodeCode(JobTaskEntity task, WorkflowRunEntity workflowRun) {
    String nodeCode =
        TaskOutcomePayloadSupport.payloadStringValue(
            task == null ? null : task.getTaskPayload(), "workflowNodeCode");
    if (nodeCode != null && !nodeCode.isBlank()) {
      return nodeCode;
    }
    Set<String> activeNodes =
        workflowRun == null ? Set.of() : parseActiveNodes(workflowRun.getCurrentNodeCode());
    // 多活动节点同时缺 workflowNodeCode 即数据错乱：fallback 到 iterator.first 会把错误节点结掉，必须拒绝。
    if (workflowRun != null && activeNodes.size() > 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.workflow.task_payload_missing_node_code",
          String.valueOf(task == null ? null : task.getId()));
    }
    if (!activeNodes.isEmpty()) {
      return activeNodes.iterator().next();
    }
    return WorkflowNodeCode.START.code();
  }

  private String resolveCurrentNodeType(JobTaskEntity task) {
    String nodeType =
        TaskOutcomePayloadSupport.payloadStringValue(
            task == null ? null : task.getTaskPayload(), "workflowNodeType");
    return nodeType == null || nodeType.isBlank() ? WorkflowNodeType.TASK.code() : nodeType;
  }

  private NodePartitionProgress resolveNodePartitionProgress(
      List<JobPartitionEntity> partitions,
      List<JobTaskEntity> tasks,
      String nodeCode,
      WorkflowRunEntity workflowRun) {
    if (nodeCode == null || nodeCode.isBlank()) {
      return new NodePartitionProgress(0, 0, 0);
    }
    Map<Long, JobPartitionEntity> partitionsById = new LinkedHashMap<>();
    for (JobPartitionEntity partition : partitions) {
      if (partition == null || partition.getId() == null) {
        continue;
      }
      partitionsById.put(partition.getId(), partition);
    }
    Set<Long> nodePartitionIds = new LinkedHashSet<>();
    for (JobTaskEntity task : tasks) {
      if (task == null || task.getJobPartitionId() == null) {
        continue;
      }
      String taskNodeCode = resolveTaskNodeCode(task, workflowRun, nodeCode);
      if (nodeCode.equals(taskNodeCode)) {
        nodePartitionIds.add(task.getJobPartitionId());
      }
    }
    long nodeSuccessCount = 0L;
    long nodeFailedCount = 0L;
    for (Long partitionId : nodePartitionIds) {
      JobPartitionEntity partition = partitionsById.get(partitionId);
      if (partition == null) {
        continue;
      }
      if (PartitionStatus.SUCCESS.code().equals(partition.getPartitionStatus())) {
        nodeSuccessCount++;
      } else if (PartitionStatus.FAILED.code().equals(partition.getPartitionStatus())) {
        nodeFailedCount++;
      }
    }
    return new NodePartitionProgress(nodePartitionIds.size(), nodeSuccessCount, nodeFailedCount);
  }

  private String resolveTaskNodeCode(
      JobTaskEntity task, WorkflowRunEntity workflowRun, String fallbackNodeCode) {
    String taskNodeCode =
        TaskOutcomePayloadSupport.payloadStringValue(
            task == null ? null : task.getTaskPayload(), "workflowNodeCode");
    if (taskNodeCode != null && !taskNodeCode.isBlank()) {
      return taskNodeCode;
    }
    if (workflowRun != null
        && workflowRun.getCurrentNodeCode() != null
        && !workflowRun.getCurrentNodeCode().isBlank()) {
      Set<String> activeNodes = parseActiveNodes(workflowRun.getCurrentNodeCode());
      if (activeNodes.size() == 1) {
        return activeNodes.iterator().next();
      }
    }
    return fallbackNodeCode;
  }

  private Set<String> parseActiveNodes(String currentNodeCode) {
    Set<String> activeNodes = new LinkedHashSet<>();
    if (currentNodeCode == null || currentNodeCode.isBlank()) {
      return activeNodes;
    }
    for (String nodeCode : currentNodeCode.split(",")) {
      if (nodeCode == null || nodeCode.isBlank()) {
        continue;
      }
      activeNodes.add(nodeCode.trim());
    }
    return activeNodes;
  }

  private boolean isActiveNode(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    if (latestNodeRun == null) {
      return false;
    }
    return WorkflowNodeRunStatus.READY.code().equals(latestNodeRun.getNodeStatus())
        || WorkflowNodeRunStatus.RUNNING.code().equals(latestNodeRun.getNodeStatus());
  }

  private Instant resolveNodeStartedAt(
      Long workflowRunId, String nodeCode, Instant workflowStartedAt, Instant finishedAt) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    if (latestNodeRun != null && latestNodeRun.getStartedAt() != null) {
      return latestNodeRun.getStartedAt();
    }
    if (workflowStartedAt != null) {
      return workflowStartedAt;
    }
    return finishedAt;
  }

  private String resolveInstanceEvent(
      long successCount, long failedCount, boolean allPartitionsFinished, boolean dagContinues) {
    if (!allPartitionsFinished) {
      return JobInstanceStatus.RUNNING.code();
    }
    if (dagContinues) {
      return JobInstanceStatus.RUNNING.code();
    }
    if (failedCount > 0 && successCount > 0) {
      return JobInstanceStatus.PARTIAL_FAILED.code();
    }
    if (failedCount > 0) {
      return JobInstanceStatus.FAILED.code();
    }
    return JobInstanceStatus.SUCCESS.code();
  }

  /** workflow_run 只允许进入 workflow 语义状态，不复用 job_instance 的 PARTIAL_FAILED 等口径。 */
  private String resolveWorkflowEvent(
      long failedCount, boolean allPartitionsFinished, boolean dagContinues) {
    if (!allPartitionsFinished) {
      return WorkflowRunStatus.RUNNING.code();
    }
    if (dagContinues) {
      return WorkflowRunStatus.RUNNING.code();
    }
    return failedCount > 0 ? WorkflowRunStatus.FAILED.code() : WorkflowRunStatus.SUCCESS.code();
  }

  private String resolveWorkflowCurrentNode(
      Set<String> activeNodes, String workflowStatus, String fallbackNodeCode) {
    if (activeNodes != null && !activeNodes.isEmpty()) {
      return String.join(",", activeNodes);
    }
    if (isWorkflowTerminal(workflowStatus)) {
      return WorkflowNodeCode.END.code();
    }
    return fallbackNodeCode;
  }

  private boolean isWorkflowTerminal(String workflowStatus) {
    return WorkflowRunStatus.SUCCESS.code().equals(workflowStatus)
        || WorkflowRunStatus.FAILED.code().equals(workflowStatus)
        || WorkflowRunStatus.TERMINATED.code().equals(workflowStatus);
  }

  private int nextRunSeq(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity current =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    return current == null || current.getRunSeq() == null ? 1 : current.getRunSeq() + 1;
  }

  private record NodePartitionProgress(int partitionCount, long successCount, long failedCount) {

    private boolean allFinished() {
      return partitionCount > 0 && successCount + failedCount == partitionCount;
    }
  }

  @Builder
  private record DagAdvanceContext(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      String currentNodeCode,
      NodePartitionProgress nodeProgress,
      Set<String> activeNodes,
      Instant finishedAt) {}
}
