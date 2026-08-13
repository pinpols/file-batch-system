package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
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
import io.github.pinpols.batch.orchestrator.domain.entity.NodePartitionAssignment;
import io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.FinishTaskParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateStepProgressParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateMachine;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import io.github.pinpols.batch.orchestrator.service.failure.FailureClassifier;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
 *   <li>推进 partition/job_instance/workflow_run 的状态机（含 DAG 节点切换与下一节点派发）
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

  /**
   * workflow_run.updateStatus 期望前态白名单：仅 CREATED / RUNNING 才允许 outcome 推动状态机； cancel/terminate 已把
   * run 切到 TERMINATED 后再来 outcome 应当被守护拦掉。
   */
  private static final List<String> WORKFLOW_RUN_LIVE_STATUSES =
      List.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code());

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final DefaultTaskOutcomeCollaborators collaborators;
  private final TaskOutcomeNodeRunRecorder nodeRunRecorder;
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

  public DefaultTaskOutcomeService(
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      DefaultTaskOutcomeCollaborators collaborators) {
    this.jobMappers = jobMappers;
    this.workflowMappers = workflowMappers;
    this.collaborators = collaborators;
    this.nodeRunRecorder = new TaskOutcomeNodeRunRecorder(workflowMappers);
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
    // advancePartitionAndInstance(复读计数)始终在同一把逻辑锁下顺序执行,消除了 outcome-vs-outcome 的锁顺序反转;
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

  /** 推进分区/实例状态机：统计分区完成情况，更新 job_instance 状态，处理 DAG 节点流转。 */
  private void advancePartitionAndInstance(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      Instant finishedAt) {
    // 并发 outcome 的分区计数一致性由入口处的 advisory lock(同 instance 串行化)保证,不再需要
    // whole-instance FOR UPDATE 行锁。此处普通读即可拿到一致快照(含本事务已写入的自身分区状态)。
    // perf(#5): 常规 REPORT 只需各分区状态做计数,改用 (id, partition_status) 轻量投影,避免每次 REPORT 的
    // select *(含 output_summary jsonb 大列)把 N 个分区全量拉进内存 —— 单 instance 万级 fan-out 下那是
    // O(N)/REPORT × N REPORT = O(N²) 且被 advisory lock 串行的 report choke。output_summary 只在下面
    // 「节点完成 / 实例终态」聚合产出时才按需全量再读。
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
    // perf: 只取 (job_partition_id, workflowNodeCode) 轻量投影做按节点计分区,避免 select * 拉全部 task 行
    // (task_payload / effective_parameters 两个大 JSON 列)。计数口径不变:仍以 partition 状态计成功/失败。
    List<NodePartitionAssignment> nodeAssignments =
        jobMappers.jobTaskMapper.selectNodeAssignmentsByInstance(
            command.tenantId(), task.getJobInstanceId());
    NodePartitionProgressCalculator.Result nodeProgress = NodePartitionProgressCalculator.calculate(
        statusRefs, nodeAssignments, currentNodeCode, workflowRun);
    Set<String> activeNodes = EmptyChecks.isNull(workflowRun)
        ? new LinkedHashSet<>()
        : TaskOutcomeStatePolicy.parseActiveNodes(workflowRun.getCurrentNodeCode());

    if (nodeProgress.allFinished() && EmptyChecks.isNotNull(workflowRun)) {
      // perf(#5): 节点完成时才需要 output_summary 做产出聚合,此处按需全量读(节点完成远少于每 REPORT)。
      List<JobPartitionEntity> nodeCompletionPartitions = loadPartitions(command, task);
      DagAdvanceContext advanceCtx = DagAdvanceContext.builder()
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
      advanceDagNodes(advanceCtx);
      // current_node_code 是派发期缓存，fan-out gateway 会递归创建分支但可能仍残留已完成的 FORK。
      // 节点完成后以 workflow_node_run 的最新状态重建活跃集合，避免 END 已成功却永久 RUNNING。
      activeNodes.clear();
      activeNodes.addAll(TaskOutcomeStatePolicy.resolveActiveNodeCodes(
          workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId())));
    }

    boolean dagContinues =
        EmptyChecks.isNotNull(workflowRun) && EmptyChecks.isNotEmpty(activeNodes);
    boolean jobFullyComplete = allPartitionsFinished && !dagContinues;
    // #3-1: 重新读取 instance 获取最新 version，避免并发 outcome 间版本冲突导致永久循环。
    // 此时分区行已被 FOR UPDATE 锁住，保证了分区计数的串行性，
    // 但 job_instance 本身可能被其他已完成的 outcome 更新了 version。
    // C-2.2: 重新读取 instance 获取最新 version 和状态，直接用 freshInstance 做状态机转换，
    // 避免 jobInstance 上残留过期字段导致 stateMachine 基于错误状态计算转换结果
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
      // 重试/补偿可能在旧失败终态写入后完成全部分区。此时子状态已证明失败是陈旧结果，允许受限收敛为 SUCCESS。
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
    // ADR-012: instance 级 failure_class 仅在终态且失败类（FAILED / PARTIAL_FAILED）时填；
    // SUCCESS 终态保持 NULL。来源 = 当前命令本次推断的 class（合并 worker 上报 + classifier 回退）。
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
      // worker REPORT 路径的终态切换也计入 JobLifecycleMetrics，与
      // JobInstanceTerminalStatusApplicationService（运维/超时路径）走同一 helper。
      // jobFullyComplete 决定 finishedAt 是否进入实例 — null 时 helper 走 afterCommit 时刻回退。
      collaborators
          .jobLifecycleMetricsRecorder()
          .recordCompletionAfterCommit(
              command.tenantId(),
              jobInstance.getId(),
              instanceStatus,
              jobFullyComplete ? finishedAt : null);
      // ADR-012 Stage 5: 失败终态打 failure_class 维度 metric, alert routing / 看板使用。
      if (EmptyChecks.isNotNull(instanceFailureClass)) {
        collaborators
            .meterRegistry()
            .counter(
                "batch.job.failure",
                "tenant",
                Optional.ofNullable(command.tenantId()).orElse("unknown"),
                "jobCode",
                Optional.ofNullable(jobInstance.getJobCode()).orElse("unknown"),
                "class",
                instanceFailureClass)
            .increment();
      }
      collaborators
          .jobInstanceTerminalChildStateReconciler()
          .reconcile(command.tenantId(), jobInstance.getId(), instanceStatus);
      // ADR-017 Stage 2: SUCCESS / PARTIAL_FAILED → 落 result_version (writer 自身做幂等 + 非成功类终态 skip)
      // perf(#5): 终态时才需要 output_summary 做产出聚合,此处按需全量读(实例终态每实例仅一次)。
      collaborators
          .resultVersionWriter()
          .writeOnTerminal(
              jobInstance,
              TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(
                  loadPartitions(command, task), command));
      // ADR-020 Stage 5: replay-driven 实例 → 反查 entry 推进 entry / session 状态
      if (EmptyChecks.isNotNull(jobInstance.getReplaySessionId())) {
        collaborators
            .batchDayReplayTerminalReconciler()
            .reconcileOnTerminal(
                jobInstance.getTenantId(),
                jobInstance.getReplaySessionId(),
                jobInstance.getJobCode(),
                jobInstance.getId(),
                instanceStatus);
      }
    }
    // 若本作业由 DAG 中 JOB 节点子作业拉起，需回写父侧信号
    if (jobFullyComplete && TaskOutcomeStatePolicy.isTerminalJobInstanceStatus(instanceStatus)) {
      signalParentVirtualTask(jobInstance, instanceStatus, command);
    }
    if (EmptyChecks.isNotNull(workflowRun)) {
      String workflowEvent = TaskOutcomeStatePolicy.resolveWorkflowEvent(
          failedCount,
          allPartitionsFinished,
          dagContinues,
          Boolean.TRUE.equals(workflowRun.getDryRun()));
      String workflowStatus =
          collaborators.stateMachine().transition(workflowRun, workflowEvent).toState();
      Instant workflowFinishedAt = jobFullyComplete ? finishedAt : null;
      // C-2.3: SQL 守护期望前态 {CREATED, RUNNING}，避免与运维 cancel/terminate 抢占造成 TERMINATED 覆写
      int updated =
          workflowMappers.workflowRunMapper.updateStatus(UpdateWorkflowRunStatusParam.builder()
              .tenantId(command.tenantId())
              .id(workflowRun.getId())
              .runStatus(workflowStatus)
              .currentNodeCode(TaskOutcomeStatePolicy.resolveWorkflowCurrentNode(
                  activeNodes, workflowStatus, currentNodeCode))
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
        collaborators
            .workflowTerminalOutboxService()
            .writeTerminalEvent(workflowRun, workflowStatus, workflowFinishedAt);
      }
    }
  }

  /** DAG 工作流节点推进：完成当前节点运行记录，解析并派发后继节点。 */
  private void advanceDagNodes(DagAdvanceContext ctx) {
    ctx.activeNodes().remove(ctx.currentNodeCode());
    NodeRunOutcome currentOutcome = NodeRunOutcome.builder()
        .success(ctx.nodeProgress().failedCount() == 0)
        .errorCode(ctx.command().errorCode())
        .errorMessage(ctx.command().errorMessage())
        .errorKey(ctx.command().errorKey())
        .errorArgs(ctx.command().errorArgs())
        .startedAt(nodeRunRecorder.resolveStartedAt(
            ctx.workflowRun().getId(),
            ctx.currentNodeCode(),
            ctx.workflowRun().getStartedAt(),
            ctx.finishedAt()))
        .finishedAt(ctx.finishedAt())
        .outputJson(TaskOutcomeSummaryBuilder.serializeOutputs(ctx.nodeOutputs()))
        .build();
    NodeRunKey currentKey = new NodeRunKey(
        ctx.workflowRun().getId(), ctx.currentNodeCode(), resolveCurrentNodeType(ctx.task()));
    recordNodeRunFinish(NodeRunFinishCommand.of(currentKey, currentOutcome));
    List<WorkflowDagService.DagNodeResolution> nextNodes = collaborators
        .workflowDagService()
        .resolveNextNodes(
            ctx.workflowRun().getWorkflowDefinitionId(),
            ctx.currentNodeCode(),
            ctx.nodeProgress().failedCount() == 0,
            ctx.task().getTaskPayload());
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (EmptyChecks.isNull(nextNode)) {
        continue;
      }
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        if (collaborators
            .workflowDagService()
            .isNodeReadyForDispatch(
                ctx.workflowRun().getId(),
                ctx.workflowRun().getWorkflowDefinitionId(),
                nextNode.nodeCode(),
                ctx.task().getTaskPayload())) {
          recordNodeRunStart(
              ctx.workflowRun().getId(),
              nextNode.nodeCode(),
              nextNode.nodeType(),
              ctx.finishedAt());
          // END 节点没有 worker 上报，output 永远 null
          NodeRunOutcome endOutcome = NodeRunOutcome.builder()
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
          recordNodeRunFinish(NodeRunFinishCommand.of(endKey, endOutcome));
        }
        continue;
      }
      int dispatched = collaborators
          .workflowNodeDispatchServiceProvider()
          .getObject()
          .dispatchNode(
              ctx.jobInstance(),
              ctx.workflowRun(),
              nextNode,
              ctx.task().getTaskPayload(),
              ctx.jobInstance().getTraceId());
      // P2-6：只在真正派发产生分区时把下游加入 activeNodes；否则 workflow_run.current_node_code 会写入
      // 残留节点（dispatchNode 因 readiness/已激活/cross-day 等返回 0 但 isActiveNode 偶尔仍命中并发线程
      // 刚插入的 RUNNING 行），导致 workflow 永远到不了终态。
      if (dispatched > 0 && isActiveNode(ctx.workflowRun().getId(), nextNode.nodeCode())) {
        ctx.activeNodes().add(nextNode.nodeCode());
      }
    }
    // 当前节点 FAILED 时级联将永远无法触发的 SUCCESS-edge 下游写为 SKIPPED；防止 ALL-mode join 因
    // 缺失上游 node_run 行陷入永久死锁（terminalCount/matchedCount 永远到不了 size）。
    if (ctx.nodeProgress().failedCount() > 0) {
      collaborators
          .workflowDagService()
          .cascadeSkipDownstream(
              ctx.workflowRun().getId(),
              ctx.workflowRun().getWorkflowDefinitionId(),
              ctx.currentNodeCode());
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

  /** 当 JOB 节点启动的子 Job 到达终态时，将结果应用到父 Job 中的虚拟任务， 由标准的基于分区的 DAG 推进逻辑接管后续流转。 */
  private void signalParentVirtualTask(
      JobInstanceEntity childJobInstance,
      String childInstanceStatus,
      TaskOutcomeCommand childCommand) {
    Long parentVirtualTaskId =
        ParentVirtualTaskIdResolver.resolve(childJobInstance.getParamsSnapshot());
    if (EmptyChecks.isNull(parentVirtualTaskId)) {
      return;
    }
    boolean nodeSuccess = JobInstanceStatus.SUCCESS.code().equals(childInstanceStatus);
    // 父虚拟任务不直接推父水位：子作业自己的 outcome 已经写过对应实例的 high_water_mark_out；
    // 父侧不与子作业共享水位。ADR-009: JOB 节点把子作业的 outputs 透传到父 workflow 节点（供下游 DSL 引用）。
    TaskOutcomeCommand parentCommand = TaskOutcomeCommand.builder()
        .tenantId(childJobInstance.getTenantId())
        .taskId(parentVirtualTaskId)
        .success(nodeSuccess)
        .resultSummary(JsonUtils.toJson(Map.of("childInstanceStatus", childInstanceStatus)))
        .errorCode(nodeSuccess ? null : childCommand.errorCode())
        .errorMessage(nodeSuccess ? null : childCommand.errorMessage())
        .errorKey(nodeSuccess ? null : childCommand.errorKey())
        .errorArgs(nodeSuccess ? null : childCommand.errorArgs())
        .failureClass(nodeSuccess ? null : childCommand.failureClass())
        .outputs(nodeSuccess ? childCommand.outputs() : null)
        .build();
    applyTaskOutcome(parentCommand);
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
    // 多活动节点同时缺 workflowNodeCode 即数据错乱：fallback 到 iterator.first 会把错误节点结掉，必须拒绝。
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

  private String resolveCurrentNodeType(JobTaskEntity task) {
    String nodeType = TaskOutcomePayloadSupport.payloadStringValue(
        EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "workflowNodeType");
    return EmptyChecks.isBlank(nodeType) ? WorkflowNodeType.TASK.code() : nodeType;
  }

  /**
   * perf(#5): 按需全量读取该 instance 的 {@code job_partition}(含 {@code output_summary} 大列),只用于「节点完成 /
   * 实例终态」的产出聚合。与常规 REPORT 计数路径(轻量投影)隔离,避免每次 REPORT 都拉全量。
   */
  private List<JobPartitionEntity> loadPartitions(TaskOutcomeCommand command, JobTaskEntity task) {
    return jobMappers.jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(command.tenantId(), task.getJobInstanceId(), null, null));
  }

  static Set<String> resolveActiveNodeCodes(List<WorkflowNodeRunEntity> nodeRuns) {
    return TaskOutcomeStatePolicy.resolveActiveNodeCodes(nodeRuns);
  }

  private boolean isActiveNode(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    if (EmptyChecks.isNull(latestNodeRun)) {
      return false;
    }
    return WorkflowNodeRunStatus.READY.code().equals(latestNodeRun.getNodeStatus())
        || WorkflowNodeRunStatus.RUNNING.code().equals(latestNodeRun.getNodeStatus());
  }

  @Builder
  private record DagAdvanceContext(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      String currentNodeCode,
      NodePartitionProgressCalculator.Result nodeProgress,
      Map<String, Object> nodeOutputs,
      Set<String> activeNodes,
      Instant finishedAt) {}
}
