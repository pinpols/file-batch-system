package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetFreshnessPolicyService;
import io.github.pinpols.batch.orchestrator.config.AssetFreshnessPolicyProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** JOB asset freshness SLA 扫描调度器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetFreshnessPolicyScheduler {

  private final AssetFreshnessPolicyService freshnessPolicyService;
  private final AssetFreshnessPolicyProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.asset-freshness.scan-interval-millis:60000}")
  @SchedulerLock(
      name = "asset_freshness_policy_scan",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT15S")
  public void scan() {
    if (!properties.isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    int emitted = freshnessPolicyService.scanDuePolicies(properties.getBatchLimit());
    if (emitted > 0) {
      log.warn("Asset freshness scan emitted {} alert(s)", emitted);
    }
  }
}
