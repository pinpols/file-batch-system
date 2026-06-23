package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 仅在 {@code batch.quota.runtime-store=database} 时启用：周期 reconcile PG quota_runtime_state
 * 表里过期的窗口。Redis 后端由 TTL 自动回收，本调度器不需要存在。
 */
@Component
@ConditionalOnProperty(name = "batch.quota.runtime-store", havingValue = "database")
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
