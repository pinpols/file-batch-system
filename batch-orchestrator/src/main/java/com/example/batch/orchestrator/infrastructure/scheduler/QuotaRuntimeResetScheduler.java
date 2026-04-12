package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuotaRuntimeResetScheduler {

  private final QuotaRuntimeStateService quotaRuntimeStateService;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(
      fixedDelayString = "${batch.resource-scheduler.quota-reset-scan-interval-millis:60000}")
  @SchedulerLock(name = "quota_runtime_reset", lockAtMostFor = "PT3M", lockAtLeastFor = "PT30S")
  public void scheduledReconcile() {
    reconcile();
  }

  /** 业务入口有意不加锁，以便测试和手动调用不依赖后台调度器遗留的 ShedLock 状态。 */
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!governance.resourceScheduler().isQuotaResetEnabled()) {
      return;
    }
    quotaRuntimeStateService.reconcileExpiredStates(
        governance.resourceScheduler().getQuotaResetSlidingWindowHours());
  }
}
