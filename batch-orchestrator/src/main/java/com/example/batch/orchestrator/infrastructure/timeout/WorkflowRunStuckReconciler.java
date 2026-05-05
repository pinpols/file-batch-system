package com.example.batch.orchestrator.infrastructure.timeout;

import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
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
 * workflow_run stuck 兜底（参考 docs/analysis/orchestrator-vs-industry-2026-05-03.md §2.2）。
 *
 * <p>主路径靠 {@code DefaultTaskOutcomeService} 反向推进 workflow_run；如果 task outcome 永远不来 （task 全 stuck /
 * partition 已 reclaim 但 workflow_run 没收到信号），workflow_run 会永远 RUNNING。
 *
 * <p>本 reconciler 周期扫 RUNNING 中且 {@code updated_at} 早于 stuck threshold 的 workflow_run，对每个 候选检查所有
 * {@code workflow_node_run}：
 *
 * <ul>
 *   <li>有 node 仍 READY/RUNNING → 不动（可能正常长跑或下游正在推进，由 timeout enforcer / lease reclaim 兜底）
 *   <li>所有 node 终态 (SUCCESS/FAILED/SKIPPED)：
 *       <ul>
 *         <li>任一 FAILED → CAS finalize 为 FAILED
 *         <li>全部 SUCCESS / SKIPPED → CAS finalize 为 SUCCESS
 *       </ul>
 * </ul>
 *
 * <p>CAS 守护 expectedStatuses=[RUNNING]，命中 0 行说明并发已推进，安静跳过。
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
        FinalizeAction action = decideFinalization(wr);
        if (action == FinalizeAction.SKIP) {
          skipped++;
          continue;
        }
        boolean ok = applyFinalize(wr, action);
        if (ok) {
          finalized++;
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
      log.warn("workflow_run stuck reconcile failed: {}", ex.getMessage(), ex);
    } finally {
      running.set(false);
    }
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
    String targetStatus =
        action == FinalizeAction.SUCCESS
            ? WorkflowRunStatus.SUCCESS.code()
            : WorkflowRunStatus.FAILED.code();
    UpdateWorkflowRunStatusParam param =
        UpdateWorkflowRunStatusParam.builder()
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
}
