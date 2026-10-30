package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.TenantDirectoryMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按租户维持活动告警并补发未获成功回执的关闭通知；单租户失败不阻塞其它租户。 */
@Component
@RequiredArgsConstructor
public class AlertmanagerReemitScheduler {
  private final AlertmanagerEmitProperties properties;
  private final AlertmanagerEmitPublisher publisher;
  private final AlertmanagerReemitService service;
  private final TenantDirectoryMapper tenantDirectory;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "#{${batch.alert.am-emit.resend-interval-seconds:60} * 1000}")
  @SchedulerLock(name = "am_alert_reemit", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
  public void reemit() {
    boolean skipped = !properties.isEnabled()
        || !publisher.isEnabled()
        || properties.getResendIntervalSeconds() <= 0
        || gracefulShutdown.isDraining();
    if (skipped) {
      return;
    }
    List<String> tenants;
    try {
      tenants = tenantDirectory.selectAllTenantIds();
    } catch (RuntimeException failure) {
      SwallowedExceptionLogger.warn(
          AlertmanagerReemitScheduler.class, "catch:reemit-directory", failure);
      return;
    }
    for (String tenant : tenants) {
      try {
        RlsTenantContextHolder.runWithTenant(tenant, () -> {
          service.reemitTenant(tenant);
          return null;
        });
      } catch (RuntimeException failure) {
        SwallowedExceptionLogger.warn(
            AlertmanagerReemitScheduler.class, "catch:reemit-tenant", failure);
      }
    }
  }
}
