package io.github.pinpols.batch.worker.core.reportoutbox;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Refuses unmarked report-outbox enable/disable and PLATFORM_PG/SQLITE changes for a worker
 * service.
 */
@Slf4j
@Component
@ConditionalOnBean(name = "dataSource")
public class WorkerReportOutboxBackendGuard implements ApplicationRunner, Ordered {

  private final StatefulBackendGuard guard;
  private final WorkerReportOutboxProperties properties;
  private final ApplicationNameProvider applicationNameProvider;
  private final RuntimeInfrastructureInspector infrastructureInspector;

  public WorkerReportOutboxBackendGuard(
      @Qualifier("dataSource") DataSource platformDataSource,
      WorkerReportOutboxProperties properties,
      ApplicationNameProvider applicationNameProvider,
      RuntimeInfrastructureInspector infrastructureInspector) {
    this.guard = new StatefulBackendGuard(platformDataSource);
    this.properties = properties;
    this.applicationNameProvider = applicationNameProvider;
    this.infrastructureInspector = infrastructureInspector;
  }

  @Override
  public void run(ApplicationArguments args) {
    StatefulBackendGuard.DesiredBackend desired = desiredBackend();
    StatefulBackendGuard.GuardResult result = guard.verify(desired);
    log.info(
        "worker report outbox backend guard {}: feature={}, backend={}, identity={}, generation={}",
        result.action(),
        desired.featureKey(),
        desired.backend(),
        desired.backendIdentity(),
        result.generation());
  }

  StatefulBackendGuard.DesiredBackend desiredBackend() {
    String applicationName = applicationNameProvider.name("batch-worker-unknown");
    String featureKey = "worker-report-outbox:" + applicationName;
    if (!properties.isEnabled()) {
      return new StatefulBackendGuard.DesiredBackend(
          featureKey,
          "disabled",
          "disabled",
          properties.getBackendGuard().getCutoverId(),
          applicationName);
    }

    WorkerReportOutboxStorage storage = properties.getStorage();
    String backend = storage.name().toLowerCase();
    String identity =
        switch (storage) {
          case PLATFORM_PG ->
            StatefulBackendIdentity.database(infrastructureInspector.datasourceUrl());
          case SQLITE -> StatefulBackendIdentity.sqlite(properties.resolveSqlitePath());
        };
    return new StatefulBackendGuard.DesiredBackend(
        featureKey,
        backend,
        identity,
        properties.getBackendGuard().getCutoverId(),
        applicationName);
  }

  @Override
  public int getOrder() {
    return HIGHEST_PRECEDENCE + 20;
  }
}
