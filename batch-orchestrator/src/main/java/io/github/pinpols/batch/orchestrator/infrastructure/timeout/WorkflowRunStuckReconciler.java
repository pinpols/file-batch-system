package io.github.pinpols.batch.orchestrator.infrastructure.timeout;

import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * workflow_run stuck 回退（参考 docs/archive/audit/orchestrator-vs-industry-2026-05-03.md §2.2）。
 *
 * <p>主路径靠 {@code DefaultTaskOutcomeService} 反向推进 workflow_run；如果 task outcome 永远不来 （task 全 stuck /
 * partition 已 reclaim 但 workflow_run 没收到信号），workflow_run 会永远 RUNNING。
 *
 * <p>本 reconciler 周期扫 RUNNING 中且 {@code updated_at} 早于 stuck threshold 的 workflow_run，先根据已持久化的成功节点重放
 * 未完成的 DAG 推进（覆盖进程在 resume 状态提交后丢失 after-commit 回调的窗口），再检查节点状态：
 *
 * <ul>
 *   <li>有 node 仍 READY/RUNNING → 不动（可能正常长跑或下游正在推进，由 timeout enforcer / lease reclaim 回退）
 *   <li>所有 node 终态 (SUCCESS/FAILED/SKIPPED)：
 *       <ul>
 *         <li>任一 FAILED → CAS finalize 为 FAILED
 *         <li>全部 SUCCESS / SKIPPED → CAS finalize 为 SUCCESS
 *       </ul>
 * </ul>
 *
 * <p>CAS 守护 expectedStatuses=[RUNNING]，命中 0 行说明并发已推进，安静跳过。现有节点派发幂等围栏防止恢复扫描重复创建任务。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.workflow.stuck-reconcile",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class WorkflowRunStuckReconciler {

  private static final Set<String> TERMINAL_NODE_STATUSES = Set.of("SUCCESS", "FAILED", "SKIPPED");
  private static final Set<String> EXPECTED_RUNNING = Set.of(WorkflowRunStatus.RUNNING.code());

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final WorkflowRunResumeProgressor workflowRunResumeProgressor;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "${batch.workflow.stuck-reconcile.poll-interval-millis:120000}")
  @SchedulerLock(
      name = "workflow_run_stuck_reconcile",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT30S")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      // stuck 判定阈值：updated_at < now - threshold（默认 30min — 比 timeout enforcer 60s 宽松，
      // 让 task outcome 有充分时间正常 propagate；阈值过短可能误判正常长跑 workflow）
      Duration threshold =
          Duration.ofSeconds(governance.timeout().getWorkflowStuckThresholdSeconds());
      Instant stuckBefore = BatchDateTimeSupport.utcNow().minus(threshold);
      int batchSize = Math.max(1, governance.timeout().getBatchSize());
      List<WorkflowRunEntity> candidates =
          workflowRunMapper.selectStuckRunningCandidates(stuckBefore, batchSize);
      if (candidates.isEmpty()) {
        return;
      }
      int finalized = 0;
      int skipped = 0;
      for (WorkflowRunEntity wr : candidates) {
        CandidateResult result =
            RlsTenantContextHolder.runWithTenant(wr.getTenantId(), () -> reconcileCandidate(wr));
        if (result == CandidateResult.FINALIZED) {
          finalized++;
        } else if (result == CandidateResult.ACTIVE_NODES) {
          skipped++;
        }
      }
      if (finalized > 0 || skipped > 0) {
        log.info(
            "workflow_run stuck reconcile tick: scanned={} finalized={} skipped_active_nodes={}",
            candidates.size(),
            finalized,
            skipped);
        meterRegistry.counter("batch.workflow.stuck.finalized.total").increment(finalized);
      }
    } catch (RuntimeException ex) {
      log.warn("workflow_run stuck reconcile failed", ex);
    } finally {
      running.set(false);
    }
  }

  private CandidateResult reconcileCandidate(WorkflowRunEntity candidate) {
    // 进程可能在恢复状态提交后、事务提交回调触发 DAG
    // 推进前停止。先根据已持久化的成功节点运行记录协调，
    // 再判断过期根运行是否应进入终态；派发守卫会安全处理已活动节点。
    workflowRunResumeProgressor.resume(candidate.getTenantId(), candidate.getId());
    WorkflowRunEntity refreshed =
        workflowRunMapper.selectById(candidate.getTenantId(), candidate.getId());
    boolean refreshedRunIsMissingOrNotRunning =
        EmptyChecks.isNull(refreshed)
            || !WorkflowRunStatus.RUNNING.code().equals(refreshed.getRunStatus());
    if (refreshedRunIsMissingOrNotRunning) {
      return CandidateResult.NO_ACTION;
    }
    FinalizeAction action = decideFinalization(refreshed);
    if (action == FinalizeAction.SKIP) {
      return CandidateResult.ACTIVE_NODES;
    }
    return applyFinalize(refreshed, action) ? CandidateResult.FINALIZED : CandidateResult.NO_ACTION;
  }

  private FinalizeAction decideFinalization(WorkflowRunEntity wr) {
    List<WorkflowNodeRunEntity> nodeRuns = workflowNodeRunMapper.selectByWorkflowRunId(wr.getId());
    if (nodeRuns.isEmpty()) {
      // 完全没有 node_run（异常状态：workflow 启动后没创建任何节点）→ 标 FAILED 让运维介入
      return FinalizeAction.FAIL;
    }
    boolean anyActive = false;
    boolean anyFailed = false;
    for (WorkflowNodeRunEntity n : nodeRuns) {
      if (!TERMINAL_NODE_STATUSES.contains(n.getNodeStatus())) {
        anyActive = true;
        break;
      }
      if ("FAILED".equals(n.getNodeStatus())) {
        anyFailed = true;
      }
    }
    if (anyActive) {
      return FinalizeAction.SKIP;
    }
    return anyFailed ? FinalizeAction.FAIL : FinalizeAction.SUCCESS;
  }

  private boolean applyFinalize(WorkflowRunEntity wr, FinalizeAction action) {
    boolean dryRun = Boolean.TRUE.equals(wr.getDryRun());
    String targetStatus;
    if (action == FinalizeAction.SUCCESS) {
      targetStatus =
          dryRun ? WorkflowRunStatus.SUCCESS_DRY_RUN.code() : WorkflowRunStatus.SUCCESS.code();
    } else {
      targetStatus =
          dryRun ? WorkflowRunStatus.FAILED_DRY_RUN.code() : WorkflowRunStatus.FAILED.code();
    }
    UpdateWorkflowRunStatusParam param = UpdateWorkflowRunStatusParam.builder()
        .tenantId(wr.getTenantId())
        .id(wr.getId())
        .runStatus(targetStatus)
        .currentNodeCode(wr.getCurrentNodeCode())
        .finishedAt(BatchDateTimeSupport.utcNow())
        .expectedStatuses(EXPECTED_RUNNING)
        .build();
    int rows = workflowRunMapper.updateStatus(param);
    if (rows > 0) {
      log.warn(
          "workflow_run stuck finalized: id={} tenant={} → {} (all nodes terminal)",
          wr.getId(),
          wr.getTenantId(),
          targetStatus);
      return true;
    }
    log.debug(
        "workflow_run stuck CAS miss (concurrent advance): id={} tenant={}",
        wr.getId(),
        wr.getTenantId());
    return false;
  }

  private enum FinalizeAction {
    SUCCESS,
    FAIL,
    SKIP
  }

  private enum CandidateResult {
    FINALIZED,
    ACTIVE_NODES,
    NO_ACTION
  }
}
