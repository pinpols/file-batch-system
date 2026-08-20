package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.VerifierFailureOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionWriter;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.FinishTaskParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateStepProgressParam;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateMachine;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import io.github.pinpols.batch.orchestrator.service.failure.FailureClassifier;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
@Slf4j
public class DefaultTaskOutcomeService implements TaskOutcomeService {

  private final OrchestratorJobMappers jobMappers;
  private final DefaultTaskOutcomeCollaborators collaborators;
  private final TaskOutcomeNodeRunRecorder nodeRunRecorder;
  private final TaskOutcomeInstanceProgressor instanceProgressor;
  // #1-2: CAS 冲突计数器，用于监控并发更新频率
  private final Counter casMissCounter;

  /**
   * A6:同 instance report 串行化的 advisory lock 阻塞获取耗时。争用此前只体现为端到端 report 延时,无法归因是 锁等待还是 DB 慢;这个 Timer
   * 把锁等待单独切出来(P95 上升=同 instance 高并发 report 排队)。
   */
  private final Timer advisoryLockWaitTimer;

  @Component
  public record DefaultTaskOutcomeCollaborators(
      RetryGovernanceService retryGovernanceService,
      StateMachine<Object> stateMachine,
      WorkflowDagService workflowDagService,
      ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider,
      WorkflowTerminalOutboxService workflowTerminalOutboxService,
      VerifierFailureOutboxService verifierFailureOutboxService,
      MeterRegistry meterRegistry,
      JobInstanceTerminalChildStateReconciler jobInstanceTerminalChildStateReconciler,
      ResultVersionWriter resultVersionWriter,
      BatchDayReplayTerminalReconciler batchDayReplayTerminalReconciler,
      FailureClassifier failureClassifier,
      // worker REPORT 终态写路径与 JobInstanceTerminalStatusApplicationService 复用同一
      // JobLifecycleMetrics helper，统一使用 afterCommit 调度。
      JobLifecycleMetricsRecorder jobLifecycleMetricsRecorder,
      // ADR-041 Phase1.3b:节点产出写入数据库后跨阶段 count 连续性核对(仅告警)。
      CountContinuityOutboxService countContinuityOutboxService) {}

  @Component
  public record TaskOutcomeAuxiliaryCollaborators(
      TaskOutcomeNodeRunRecorder nodeRunRecorder,
      TaskOutcomeTerminalFinalizer terminalFinalizer,
      TaskOutcomeDagProgressor dagProgressor,
      TaskOutcomeParentTaskSignaler parentTaskSignaler,
      TaskOutcomeWorkflowFinalizer workflowFinalizer) {}

  @Autowired
  public DefaultTaskOutcomeService(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      DefaultTaskOutcomeCollaborators collaborators,
      TaskOutcomeAuxiliaryCollaborators auxiliaryCollaborators,
      TaskOutcomeInstanceProgressor instanceProgressor) {
    this.jobMappers = jobMappers;
    this.collaborators = collaborators;
    this.nodeRunRecorder = auxiliaryCollaborators.nodeRunRecorder();
    this.instanceProgressor = instanceProgressor;
    this.casMissCounter = Counter.builder("batch.orchestrator.cas.miss")
        .description("CAS miss count during optimistic locking updates")
        .register(collaborators.meterRegistry());
    this.advisoryLockWaitTimer = Timer.builder("batch.report.advisory_lock.wait")
        .description(
            "Blocking wait to acquire the per-instance pg_advisory_xact_lock that serializes"
                + " concurrent reports for the same job_instance.")
        .publishPercentileHistogram()
        .register(collaborators.meterRegistry());
  }

  /**
   * 保留给历史纯单元测试的兼容构造器；Spring 生产装配始终使用上面的完整构造器注入协作者。
   */
  @Deprecated(forRemoval = false)
  public DefaultTaskOutcomeService(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      DefaultTaskOutcomeCollaborators collaborators) {
    this(
        jobMappers,
        workflowMappers,
        collaborators,
        compatibilityAuxiliaryCollaborators(workflowMappers, collaborators));
  }

  private DefaultTaskOutcomeService(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      DefaultTaskOutcomeCollaborators collaborators,
      TaskOutcomeAuxiliaryCollaborators auxiliaryCollaborators) {
    this(
        jobMappers,
        workflowMappers,
        collaborators,
        auxiliaryCollaborators,
        new TaskOutcomeInstanceProgressor(
            jobMappers,
            workflowMappers,
            collaborators,
            auxiliaryCollaborators.terminalFinalizer(),
            auxiliaryCollaborators.dagProgressor(),
            auxiliaryCollaborators.parentTaskSignaler(),
            auxiliaryCollaborators.workflowFinalizer()));
  }

  private static TaskOutcomeAuxiliaryCollaborators compatibilityAuxiliaryCollaborators(
      OrchestratorWorkflowMappers workflowMappers, DefaultTaskOutcomeCollaborators collaborators) {
    TaskOutcomeNodeRunRecorder nodeRunRecorder = new TaskOutcomeNodeRunRecorder(workflowMappers);
    return new TaskOutcomeAuxiliaryCollaborators(
        nodeRunRecorder,
        new TaskOutcomeTerminalFinalizer(
            collaborators.jobLifecycleMetricsRecorder(),
            collaborators.meterRegistry(),
            collaborators.jobInstanceTerminalChildStateReconciler(),
            collaborators.resultVersionWriter(),
            collaborators.batchDayReplayTerminalReconciler()),
        new TaskOutcomeDagProgressor(
            workflowMappers,
            collaborators.workflowDagService(),
            collaborators.workflowNodeDispatchServiceProvider(),
            nodeRunRecorder,
            collaborators.countContinuityOutboxService()),
        new TaskOutcomeParentTaskSignaler(),
        new TaskOutcomeWorkflowFinalizer(
            workflowMappers,
            collaborators.stateMachine(),
            collaborators.workflowTerminalOutboxService()));
  }

  // #8-3: 启动时验证 ObjectProvider 可正常解析，将循环依赖暴露在启动阶段而非运行时
  @PostConstruct
  void verifyLazyDependencies() {
    try {
      collaborators.workflowNodeDispatchServiceProvider().getIfAvailable();
    } catch (Exception ex) {
      log.error(
          "Failed to resolve lazy WorkflowNodeDispatchService injection; a circular dependency may exist: {}",
          ex.getMessage());
      throw new IllegalStateException(
          "Failed to resolve WorkflowNodeDispatchService from ObjectProvider; check for circular dependencies",
          ex);
    }
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunReady(
      Long workflowRunId, String nodeCode, String nodeType) {
    return nodeRunRecorder.recordReady(workflowRunId, nodeCode, nodeType);
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunStart(
      Long workflowRunId, String nodeCode, String nodeType, Instant startedAt) {
    return nodeRunRecorder.recordStart(workflowRunId, nodeCode, nodeType, startedAt);
  }

  @Override
  @Transactional
  public WorkflowNodeRunEntity recordNodeRunFinish(NodeRunFinishCommand command) {
    WorkflowNodeRunEntity finished = nodeRunRecorder.recordFinish(command);
    // ADR-041 Phase1.3b:本节点产出已写入数据库,同事务核跨阶段 count 连续性(仅告警,不翻转状态)。
    if (command.success()) {
      collaborators
          .countContinuityOutboxService()
          .checkContinuity(command.workflowRunId(), command.nodeCode(), command.outputJson());
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
    JobTaskEntity task = jobMappers.jobTaskMapper.selectById(command.tenantId(), command.taskId());
    if (EmptyChecks.isNull(task)) {
      return null;
    }
    // 只处理 RUNNING → terminal 的一次性回报；重复回报直接返回当前状态，保证幂等。
    if (!TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
      log.info(
          "task outcome ignored (already {}): taskId={}", task.getTaskStatus(), command.taskId());
      return task;
    }
    // workerId 非空时校验 worker 归属，防止恶意/错误 worker 伪造回报。
    if (EmptyChecks.isNotNull(command.workerId())
        && !command.workerId().equals(task.getAssignedWorkerCode())) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.worker.not_owner");
    }
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    JobPartitionEntity partition =
        jobMappers.jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId());
    // R3-P1-10 一致性:report 纳入 partition invocation fence,与 renew/updateOutputSummary 同一守卫宇宙。
    // partition-bound 且该 partition 已被 CLAIM(current_invocation_id 非空)的任务,其 report 必须携带
    // 匹配的 invocationId —— 缺失或不匹配即拒(error.task.invocation_mismatch)。此前 report 是唯一"带
    // invocationId 才校验,缺就跳过"的租约操作:reclaim 重派后陈旧 worker 的迟到 report(不带/带旧
    // invocationId)会绕过 fence,用旧结果通过 finishTask CAS(RUNNING+version 为报告时重读的当前值)终结
    // 已被 I2 重新领取的任务,造成 double-executor 且 I2 真结果被丢。收紧后陈旧 report 在写任何状态前即被拒。
    // 豁免语义与 renew 对齐:虚拟父任务(ADR-009)/尚未 CLAIM 的 partition 其 current_invocation_id 恒为
    // null → null 放行,不强制携带 invocationId(signalParentVirtualTask 的内部 report 天然满足)。
    if (EmptyChecks.isNotNull(task.getJobPartitionId())
        && EmptyChecks.isNotNull(partition)
        && EmptyChecks.isNotNull(partition.getCurrentInvocationId())
        && !partition.getCurrentInvocationId().equals(command.partitionInvocationId())) {
      throw BizException.of(ResultCode.CONFLICT, "error.task.invocation_mismatch");
    }
    JobInstanceEntity jobInstance =
        jobMappers.jobInstanceMapper.selectById(command.tenantId(), task.getJobInstanceId());
    // 失败时是否进入重试：由治理层统一决策（NONE/预算耗尽 → dead-letter；否则写 retry_schedule）。
    boolean retryScheduled = !command.success()
        && EmptyChecks.isNotNull(partition)
        && EmptyChecks.isNotNull(jobInstance)
        && collaborators
            .retryGovernanceService()
            .scheduleRetryIfNecessary(
                task, partition, jobInstance, command.errorCode(), command.errorMessage());
    String resolvedFailureClass = command.success()
        ? null
        : collaborators
            .failureClassifier()
            .classify(command.failureClass(), null)
            .code();
    int updated = jobMappers.jobTaskMapper.finishTask(FinishTaskParam.builder()
        .tenantId(command.tenantId())
        .id(command.taskId())
        .taskStatus(command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code())
        .expectedStatus(TaskStatus.RUNNING.code())
        .resultSummary(command.resultSummary())
        .errorCode(command.errorCode())
        .errorMessage(command.errorMessage())
        .errorKey(command.errorKey())
        .errorArgs(command.errorArgs())
        .failureClass(resolvedFailureClass)
        .expectedVersion(task.getVersion())
        .build());
    if (updated <= 0) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "task already finished by concurrent update: taskId=" + command.taskId());
    }

    // 死锁防护 + 同 instance 串行化(perf):此前对该 instance 的【全部兄弟分区】whole-instance FOR UPDATE
    // (纯排序加锁、丢结果)会把同 instance 的并发 report 完全串行、且是 O(N) 行锁。改用事务级 advisory lock:
    // 对 (tenantId, jobInstanceId) 取一把 pg_advisory_xact_lock,把同 instance 的并发 outcome 串行化,
    // 随事务自动释放。锁的是逻辑序而非行,因此:1) 下方 markStatus / updateOutputSummary(单分区写锁)与
    // instanceProgressor(复读计数)始终在同一把逻辑锁下顺序执行,消除了 outcome-vs-outcome 的锁顺序反转;
    // 2) outcome 不再批量锁全兄弟分区,消除了旧的「reclaim asc N 行 / outcome desc N 行」环形死锁。
    // 注意(边界):advisory lock 只串行化 outcome-vs-outcome,reclaim 不取该 advisory lock;outcome 仍按
    // task(finishTask)→ partition(markStatus)取行锁,与 reclaim 的 partition→task 相反,单 outcome × 单
    // reclaim
    // 对同一 (task, partition) 的 2 行反转不由本 advisory lock 解决 —— 那条已由 PartitionReclaimUnit 对 task 行
    // 改用 FOR UPDATE NOWAIT 让路修复(见 OutcomeVsReclaimDeadlockIntegrationTest)。
    if (EmptyChecks.isNotNull(task.getJobInstanceId())) {
      // A6:锁的阻塞获取耗时单独计时(争用归因)。record(Runnable) 只关心墙钟时长,返回值不用。
      advisoryLockWaitTimer.record(() -> jobMappers.jobInstanceMapper.acquireInstanceAdvisoryLock(
          command.tenantId(), task.getJobInstanceId()));
    }

    if (command.success()) {
      applySuccessOutcome(command, partition);
      // ADR-030 §F：worker 上报的 ContentVerifier 失败 → 同事务写 outbox_event(verifier.failure.v1)。
      // 软告警语义：不翻转 task SUCCESS，仅产出可订阅的事件供告警面板消费。
      collaborators.verifierFailureOutboxService().writeVerifierFailures(command, task);
      // ExecutionMode.INCREMENTAL:把 worker 上报的新水位回写到 job_instance。null/空跳过
      // (保留旧值,下次启动时同 IN 不变);仅成功路径推水位,失败/重试不应推进。
      if (EmptyChecks.isNotBlank(command.highWaterMarkOut())) {
        int wmUpdated = jobMappers.jobInstanceMapper.updateHighWaterMarkOut(
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
    if (EmptyChecks.isNotNull(partition)) {
      // R3-P0-5：传 invocationId 作为 CAS 守卫，迟到的旧 invocation 的 report 不再覆盖新 output。
      // command 携带 partitionInvocationId 来自 task CLAIM 时的快照；与 partition.current_invocation_id 比对。
      jobMappers.jobPartitionMapper.updateOutputSummary(
          command.tenantId(),
          partition.getId(),
          TaskOutcomeSummaryBuilder.buildOutputSummary(command, task),
          command.partitionInvocationId());
    }
    // step 镜像用于"按 step 维度"看执行状态/重试次数，与 task/partition 状态保持一致口径。
    updateStepInstanceProgress(command, task, retryScheduled, finishedAt);
    if (EmptyChecks.isNotNull(jobInstance)) {
      instanceProgressor.advance(command, task, jobInstance, finishedAt, this::applyTaskOutcome);
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
    if (EmptyChecks.isNull(partition)) {
      return;
    }
    // C-8: 检查 markStatus 返回值，0 行表示并发更新已推进分区状态，保证分区与任务状态在同一事务内一致
    int partitionUpdated =
        jobMappers.jobPartitionMapper.markStatus(MarkPartitionStatusParam.builder()
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
    if (EmptyChecks.isNull(partition)) {
      return;
    }
    if (retryScheduled) {
      // 进入 RETRYING：partition 先标记为 RETRYING，实际重排队由 retry scheduler → outbox 完成。
      int retryUpdated = jobMappers.jobPartitionMapper.markRetrying(
          command.tenantId(),
          partition.getId(),
          Optional.ofNullable(partition.getRetryCount()).orElse(0) + 1,
          PartitionStatus.RETRYING.code(),
          partition.getVersion());
      warnIfCasMiss(retryUpdated, "partition markRetrying", partition.getId());
    } else {
      int failUpdated = jobMappers.jobPartitionMapper.markStatus(MarkPartitionStatusParam.builder()
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

  private void updateStepInstanceProgress(
      TaskOutcomeCommand command, JobTaskEntity task, boolean retryScheduled, Instant finishedAt) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(task)) {
      return;
    }
    JobStepInstanceEntity stepInstance =
        jobMappers.jobStepInstanceMapper.selectByJobTaskId(command.tenantId(), task.getId());
    if (EmptyChecks.isNull(stepInstance)) {
      return;
    }
    String nextStatus = retryScheduled
        ? "RETRYING"
        : command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code();
    int currentRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0);
    int nextRetryCount = retryScheduled ? currentRetryCount + 1 : currentRetryCount;
    int updated = jobMappers.jobStepInstanceMapper.updateProgress(UpdateStepProgressParam.builder()
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
}
