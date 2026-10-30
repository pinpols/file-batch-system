package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.VerifierFailureOutboxService;
import io.github.pinpols.batch.orchestrator.application.scheduler.WaitingCapacityReleasedEvent;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunReportLock;
import io.github.pinpols.batch.orchestrator.domain.command.TaskExecutionResult;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.context.TaskOutcomePersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.FinishTaskParam;
import io.github.pinpols.batch.orchestrator.domain.param.JobPartitionMarkRetryingParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateStepProgressParam;
import io.github.pinpols.batch.orchestrator.service.failure.FailureClassifier;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * worker 回报（report）后的"状态推进中枢"。
 *
 * <p>在本系统中，worker 不直接修改 orchestrator 的运行态表，而是通过 HTTP {@code /internal/tasks/{taskId}/report}
 * 回报执行结果；orchestrator 在这里统一完成：
 *
 * <ul>
 *   <li>写入 task 的终态（SUCCESS/FAILED/CANCELLED）
 *   <li>根据失败决定是否进入重试（写 retry_schedule，并把 partition/task/step 标记为 RETRYING）
 *   <li>委托协作者推进 partition/job_instance/workflow_run 的状态机（含 DAG 节点切换与下一节点派发）
 *   <li>更新 step 镜像 {@code job_step_instance}（用于审计/可视化口径一致）
 * </ul>
 *
 * <p>重要约束：
 *
 * <ul>
 *   <li>只接受 RUNNING 状态的 task 回报，避免重复 report 导致状态回跳。
 *   <li>并发冲突靠 DB 乐观锁/条件更新回退（更新行数为 0 → STATE_CONFLICT）。
 * </ul>
 *
 * <p>该类是唯一的 report 状态推进入口，原因是一次 worker 回报必须在同一事务内完成 task 终态、重试决定、分区/实例聚合和 DAG 推进。
 * 如果这些动作分散到多个异步消费者，父级可能在子任务终态写入前被错误判定完成，或在重复回报时重复派发下一节点。因此这里保留状态机的集中决策，
 * 用租户条件、状态 CAS 和 instance 级 advisory lock 把 at-least-once 回报收敛为一次有效状态转移。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTaskOutcomeService implements TaskOutcomeService {

  private static final String CANCELLED_ERROR_CODE = "WORKER_EXECUTION_CANCELLED";
  private static final String UNKNOWN_NON_IDEMPOTENT_OUTCOME_MESSAGE =
      "A non-idempotent task may have applied external side effects; automatic replay is blocked pending reconciliation.";

  private final OrchestratorJobMappers jobMappers;
  private final RetryGovernanceService retryGovernanceService;
  private final VerifierFailureOutboxService verifierFailureOutboxService;
  private final FailureClassifier failureClassifier;
  private final CountContinuityOutboxService countContinuityOutboxService;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider;
  private final MeterRegistry meterRegistry;
  private final TaskOutcomeNodeRunRecorder nodeRunRecorder;
  private final TaskOutcomeInstanceProgressor instanceProgressor;
  private final WorkflowRunReportLock workflowRunReportLock;
  private Counter casMissCounter;
  /** 同实例 report 的锁等待独立计量，避免把数据库竞争误判为 Worker 执行慢。 */
  private Timer advisoryLockWaitTimer;

  // 在容器完成注入后注册指标并校验惰性依赖，测试 fixture 显式调用同一初始化入口。
  @PostConstruct
  void initialize() {
    casMissCounter = Counter.builder("batch.orchestrator.cas.miss")
        .description("CAS miss count during optimistic locking updates")
        .register(meterRegistry);
    advisoryLockWaitTimer = Timer.builder("batch.report.advisory_lock.wait")
        .description(
            "Blocking wait to acquire the per-instance pg_advisory_xact_lock that serializes"
                + " concurrent reports for the same job_instance.")
        .publishPercentileHistogram()
        .register(meterRegistry);
    try {
      workflowNodeDispatchServiceProvider.getIfAvailable();
    } catch (Exception ex) {
      log.error(
          "Failed to resolve lazy WorkflowNodeDispatchService injection; a circular dependency may exist: {}",
          SwallowedExceptionLogger.summary(ex));
      throw new IllegalStateException(
          "Failed to resolve WorkflowNodeDispatchService from ObjectProvider; check for circular dependencies",
          ex);
    }
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunFinish(NodeRunFinishCommand command) {
    WorkflowNodeRunEntity finished = nodeRunRecorder.recordFinish(command);
    // ADR-041 Phase1.3b:本节点产出已写入数据库,同事务核跨阶段 count 连续性(仅告警,不翻转状态)。
    if (command.success()) {
      countContinuityOutboxService.checkContinuity(
          command.workflowRunId(), command.nodeCode(), command.outputJson());
    }
    return finished;
  }

  @Override
  @Transactional
  @Timed(
      value = "batch.task.report",
      description = "worker REPORT → orchestrator 状态机推进的端到端延时(含 lookup + 重试入队 + 父级 join)")
  public JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command) {
    // 入口语义：worker report → orchestrator 在单事务内完成"任务完成 +（可选）重试入队 + 状态机推进"。
    if (EmptyChecks.isNull(command)) {
      return null;
    }
    TaskOutcomePersistenceContext persistenceContext =
        jobMappers.jobTaskMapper.selectOutcomePersistenceContext(
            command.tenantId(), command.taskId());
    if (EmptyChecks.isNull(persistenceContext)
        || EmptyChecks.isNull(persistenceContext.getTask())) {
      return null;
    }
    JobTaskEntity task = persistenceContext.getTask();
    // 只处理 RUNNING → terminal 的一次性回报；重复回报直接返回当前状态，保证幂等。
    if (!TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
      log.info(
          "task outcome ignored (already {}): taskId={}", task.getTaskStatus(), command.taskId());
      return task;
    }
    // 同一实例的状态写入必须统一遵循 instance advisory lock -> task -> partition 的锁序。
    // 锁前只允许做无锁读取和参数校验；否则并发 outcome 会各自持有 task 行再等待同一实例锁，
    // 持锁线程收敛实例子状态时又可能等待这些 task 行，形成 advisory/row-lock 环形死锁。
    if (EmptyChecks.isNotNull(task.getJobInstanceId())) {
      Long jobInstanceId = task.getJobInstanceId();
      advisoryLockWaitTimer.record(() -> jobMappers.jobInstanceMapper.acquireInstanceAdvisoryLock(
          command.tenantId(), jobInstanceId));
      workflowRunReportLock.acquireForJobInstance(command.tenantId(), jobInstanceId);
    }
    // 围栏后重新读取。非幂等启动标记和租约回收共用同一个
    // advisory 锁；等待任一操作完成的报告必须读取其已提交的
    // Task/分区状态，再决定是否允许重放。
    persistenceContext = jobMappers.jobTaskMapper.selectOutcomePersistenceContext(
        command.tenantId(), command.taskId());
    if (EmptyChecks.isNull(persistenceContext)
        || EmptyChecks.isNull(persistenceContext.getTask())) {
      return null;
    }
    task = persistenceContext.getTask();
    if (!TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
      log.info(
          "task outcome ignored after instance fence (already {}): taskId={}",
          task.getTaskStatus(),
          command.taskId());
      return task;
    }
    // 根据围栏后的快照检查所有者和调用标识，不使用加锁前的读取结果。
    requireAssignedWorker(command, task);
    JobPartitionEntity partition = persistenceContext.getPartition();
    // R3-P1-10 一致性:report 纳入 partition invocation fence,与 renew/updateOutputSummary 同一守卫宇宙。
    requireCurrentInvocation(command, task, partition);
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    // 成功回报无需额外读取 job_instance；失败路径在实例锁后读取当前快照计算重试治理决策。
    // 实例推进也在锁内读取权威状态，避免成功路径重复点查同一热表行。
    TaskOutcomeCommand outcomeCommand = command;
    TaskExecutionResult result = outcomeCommand.executionResult();
    TaskExecutionResult.Failure failure =
        result instanceof TaskExecutionResult.Failure failed ? failed : null;
    if (EmptyChecks.isNotNull(failure) && EmptyChecks.isNotNull(task.getNonIdempotentStartedAt())) {
      outcomeCommand = command.toBuilder()
          .errorCode(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN)
          .errorMessage(UNKNOWN_NON_IDEMPOTENT_OUTCOME_MESSAGE)
          .errorKey(null)
          .errorArgs(null)
          .failureClass(FailureClass.UNKNOWN.code())
          .build();
      result = outcomeCommand.executionResult();
      failure = (TaskExecutionResult.Failure) result;
    }
    boolean cancellationOutcome = isCancellationOutcome(task, outcomeCommand, failure);
    JobInstanceEntity retryContextInstance =
        !cancellationOutcome && EmptyChecks.isNotNull(failure) && EmptyChecks.isNotNull(partition)
            ? jobMappers.jobInstanceMapper.selectById(command.tenantId(), task.getJobInstanceId())
            : null;
    // 失败时是否进入重试：由治理层统一决策（NONE/预算耗尽 → dead-letter；否则写 retry_schedule）。
    boolean retryScheduled = !cancellationOutcome
        && EmptyChecks.isNotNull(failure)
        && EmptyChecks.isNotNull(partition)
        && EmptyChecks.isNotNull(retryContextInstance)
        && retryGovernanceService.scheduleRetryIfNecessary(
            task, partition, retryContextInstance, failure.errorCode(), failure.errorMessage());
    String resolvedFailureClass = EmptyChecks.isNull(failure) || cancellationOutcome
        ? null
        : failureClassifier.classify(failure.failureClass(), null).code();
    FinishTaskParam.FinishTaskParamBuilder finishTaskParamBuilder =
        FinishTaskParam.builder().tenantId(command.tenantId()).id(command.taskId());
    String resolvedTaskStatus = resolveTaskStatus(outcomeCommand, cancellationOutcome);
    JobTaskEntity finishedTask = jobMappers.jobTaskMapper.finishTask(finishTaskParamBuilder
        .taskStatus(resolvedTaskStatus)
        .expectedStatus(TaskStatus.RUNNING.code())
        .resultSummary(outcomeCommand.resultSummary())
        .errorCode(outcomeCommand.errorCode())
        .errorMessage(outcomeCommand.errorMessage())
        .errorKey(outcomeCommand.errorKey())
        .errorArgs(outcomeCommand.errorArgs())
        .failureClass(resolvedFailureClass)
        .expectedVersion(task.getVersion())
        .build());
    if (EmptyChecks.isNull(finishedTask)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "task already finished by concurrent update: taskId=" + command.taskId());
    }

    String outputSummary = TaskOutcomeSummaryBuilder.buildOutputSummary(outcomeCommand, task);
    JobInstanceEntity transitionedInstance;
    if (result instanceof TaskExecutionResult.Success success) {
      transitionedInstance = applySuccessOutcome(outcomeCommand, partition, outputSummary);
      // ADR-030 §F：worker 上报的 ContentVerifier 失败 → 同事务写 outbox_event(verifier.failure.v1)。
      // 软告警语义：不翻转 task SUCCESS，仅产出可订阅的事件供告警面板消费。
      verifierFailureOutboxService.writeVerifierFailures(
          outcomeCommand, task, success.verifierFailures());
      // ExecutionMode.INCREMENTAL:把 worker 上报的新水位回写到 job_instance。null/空跳过
      // (保留旧值,下次启动时同 IN 不变);仅成功路径推水位,失败/重试不应推进。
      advanceHighWaterMark(outcomeCommand, task, success);
    } else {
      transitionedInstance = applyFailureOutcome(
          outcomeCommand, partition, retryScheduled, cancellationOutcome, outputSummary);
    }
    if (EmptyChecks.isNotNull(partition) && retryScheduled) {
      // RETRYING 使用独立状态更新；保留 invocation fence，防止迟到报告覆盖下一次执行的输出。
      // SUCCESS/最终 FAILED 已把摘要合并进 markStatus，同一 CAS 原子落库，避免每个终态多一次 UPDATE/WAL。
      jobMappers.jobPartitionMapper.updateOutputSummary(
          outcomeCommand.tenantId(),
          partition.getId(),
          outputSummary,
          outcomeCommand.partitionInvocationId());
    }
    // step 镜像用于"按 step 维度"看执行状态/重试次数，与 task/partition 状态保持一致口径。
    updateStepInstanceProgress(
        outcomeCommand, task, retryScheduled, cancellationOutcome, finishedAt, outputSummary);
    // 终态 CTE 会同步递增 job_instance 的分区计数和 version，并直接返回权威快照；虚拟任务、重试路径
    // 或 CAS 未命中时才回读。两条路径都位于 instance advisory lock 保护范围内。
    JobInstanceEntity jobInstance = EmptyChecks.isNotNull(transitionedInstance)
        ? transitionedInstance
        : jobMappers.jobInstanceMapper.selectById(command.tenantId(), task.getJobInstanceId());
    if (EmptyChecks.isNotNull(jobInstance)) {
      instanceProgressor.advance(outcomeCommand, task, jobInstance, finishedAt);
    }
    if (!retryScheduled) {
      // AFTER_COMMIT 监听器会合并同一波 report，不能在事务内直接扫描，否则可能读到未提交状态。
      applicationEventPublisher.publishEvent(
          new WaitingCapacityReleasedEvent(outcomeCommand.tenantId()));
    }
    return finishedTask;
  }

  /** 只在成功回报内推进水位，保持原 CAS 和迟到分区保护。 */
  private void requireCurrentInvocation(
      TaskOutcomeCommand command, JobTaskEntity task, JobPartitionEntity partition) {
    boolean reportInvocationDoesNotMatchPartition = EmptyChecks.isNotNull(task.getJobPartitionId())
        && EmptyChecks.isNotNull(partition)
        && EmptyChecks.isNotNull(partition.getCurrentInvocationId())
        && !partition.getCurrentInvocationId().equals(command.partitionInvocationId());
    if (reportInvocationDoesNotMatchPartition) {
      throw BizException.of(ResultCode.CONFLICT, "error.task.invocation_mismatch");
    }
  }

  private void requireAssignedWorker(TaskOutcomeCommand command, JobTaskEntity task) {
    if (EmptyChecks.isNotNull(command.workerId())
        && !command.workerId().equals(task.getAssignedWorkerCode())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.worker.not_owner");
    }
  }

  private void advanceHighWaterMark(
      TaskOutcomeCommand outcomeCommand, JobTaskEntity task, TaskExecutionResult.Success success) {
    if (EmptyChecks.isNotBlank(success.highWaterMarkOut())) {
      int wmUpdated = jobMappers.jobInstanceMapper.updateHighWaterMarkOut(
          outcomeCommand.tenantId(), task.getJobInstanceId(), success.highWaterMarkOut());
      if (wmUpdated <= 0) {
        // CAS 守护拦下:更高水位已就绪 (并发 partition 回报乱序) 或新值格式非法,debug 即可。
        Long jobInstanceId = task.getJobInstanceId();
        String incomingHighWaterMark = success.highWaterMarkOut();
        String safeIncomingHighWaterMark = LogSanitizer.value(incomingHighWaterMark);
        log.debug(
            "high_water_mark_out CAS no-op for jobInstance {}: incoming={} (regression or"
                + " malformed)",
            jobInstanceId,
            safeIncomingHighWaterMark);
      }
    }
  }

  private static boolean isCancellationOutcome(
      JobTaskEntity task, TaskOutcomeCommand command, TaskExecutionResult.Failure failure) {
    return EmptyChecks.isNotNull(failure)
        && Boolean.TRUE.equals(task.getCancelRequested())
        && CANCELLED_ERROR_CODE.equals(command.errorCode());
  }

  private static String resolveTaskStatus(TaskOutcomeCommand command, boolean cancellationOutcome) {
    if (command.success()) {
      return TaskStatus.SUCCESS.code();
    }
    return cancellationOutcome ? TaskStatus.CANCELLED.code() : TaskStatus.FAILED.code();
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
  private JobInstanceEntity applySuccessOutcome(
      TaskOutcomeCommand command, JobPartitionEntity partition, String outputSummary) {
    if (EmptyChecks.isNull(partition)) {
      return null;
    }
    // C-8: null 表示并发更新已推进分区状态；回读 instance 继续沿用原有幂等兜底。
    JobInstanceEntity transitionedInstance =
        jobMappers.jobPartitionMapper.markTerminalStatusAndLoadInstance(
            MarkPartitionStatusParam.builder()
                .tenantId(command.tenantId())
                .id(partition.getId())
                .partitionStatus(PartitionStatus.SUCCESS.code())
                .runningStatus(PartitionStatus.RUNNING.code())
                .terminalStatus1(PartitionStatus.SUCCESS.code())
                .terminalStatus2(PartitionStatus.FAILED.code())
                .terminalStatus3(PartitionStatus.CANCELLED.code())
                .terminalStatus4(PartitionStatus.TERMINATED.code())
                .outputSummary(outputSummary)
                .expectedVersion(partition.getVersion())
                .build());
    warnIfCasMiss(
        EmptyChecks.isNull(transitionedInstance) ? 0 : 1,
        "partition markStatus(SUCCESS)",
        partition.getId());
    return transitionedInstance;
  }

  /** 处理失败/重试路径：根据是否安排重试，将分区标记为 RETRYING 或 FAILED。 */
  private JobInstanceEntity applyFailureOutcome(
      TaskOutcomeCommand command,
      JobPartitionEntity partition,
      boolean retryScheduled,
      boolean cancellationOutcome,
      String outputSummary) {
    if (EmptyChecks.isNull(partition)) {
      return null;
    }
    if (retryScheduled) {
      // 进入 RETRYING：partition 先标记为 RETRYING，实际重排队由 retry scheduler → outbox 完成。
      String tenantId = command.tenantId();
      Long partitionId = partition.getId();
      Integer currentRetryCount = Optional.ofNullable(partition.getRetryCount()).orElse(0);
      Integer nextRetryCount = currentRetryCount + 1;
      JobPartitionMarkRetryingParam jobPartitionMarkRetryingParam =
          JobPartitionMarkRetryingParam.builder()
              .tenantId(tenantId)
              .id(partitionId)
              .retryCount(nextRetryCount)
              .retryingStatus(PartitionStatus.RETRYING.code())
              .expectedVersion(partition.getVersion())
              .build();
      int retryUpdated = jobMappers.jobPartitionMapper.markRetrying(jobPartitionMarkRetryingParam);
      warnIfCasMiss(retryUpdated, "partition markRetrying", partition.getId());
      return null;
    } else {
      JobInstanceEntity transitionedInstance =
          jobMappers.jobPartitionMapper.markTerminalStatusAndLoadInstance(
              MarkPartitionStatusParam.builder()
                  .tenantId(command.tenantId())
                  .id(partition.getId())
                  // Worker 取消继续采用现有的失败分区契约。显式的
                  // 管理员分区取消由 InstanceManagement 处理。
                  .partitionStatus(PartitionStatus.FAILED.code())
                  .runningStatus(PartitionStatus.RUNNING.code())
                  .terminalStatus1(PartitionStatus.SUCCESS.code())
                  .terminalStatus2(PartitionStatus.FAILED.code())
                  .terminalStatus3(PartitionStatus.CANCELLED.code())
                  .terminalStatus4(PartitionStatus.TERMINATED.code())
                  .outputSummary(outputSummary)
                  .expectedVersion(partition.getVersion())
                  .build());
      warnIfCasMiss(
          EmptyChecks.isNull(transitionedInstance) ? 0 : 1,
          "partition markStatus(FAILED)",
          partition.getId());
      return transitionedInstance;
    }
  }

  private void updateStepInstanceProgress(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      boolean retryScheduled,
      boolean cancellationOutcome,
      Instant finishedAt,
      String outputSummary) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(task)) {
      return;
    }
    JobStepInstanceEntity stepInstance =
        jobMappers.jobStepInstanceMapper.selectByJobTaskId(command.tenantId(), task.getId());
    if (EmptyChecks.isNull(stepInstance)) {
      return;
    }
    String nextStatus =
        retryScheduled ? "RETRYING" : resolveTaskStatus(command, cancellationOutcome);
    int currentRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0);
    int nextRetryCount = retryScheduled ? currentRetryCount + 1 : currentRetryCount;
    UpdateStepProgressParam.UpdateStepProgressParamBuilder progressBuilder =
        UpdateStepProgressParam.builder()
            .tenantId(command.tenantId())
            .id(stepInstance.getId())
            .stepStatus(nextStatus)
            .retryCount(nextRetryCount);
    Long relatedFileId = TaskOutcomePayloadSupport.resolveRelatedFileId(task, command);
    UpdateStepProgressParam progressParam = progressBuilder
        .relatedFileId(relatedFileId)
        .resultSummary(outputSummary)
        .errorCode(command.errorCode())
        .errorMessage(command.errorMessage())
        .errorKey(command.errorKey())
        .errorArgs(command.errorArgs())
        .finishedAt(retryScheduled ? null : finishedAt)
        .expectedVersion(stepInstance.getVersion())
        .build();
    int updated = jobMappers.jobStepInstanceMapper.updateProgress(progressParam);
    if (updated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.step_progress_conflict");
    }
  }
}
