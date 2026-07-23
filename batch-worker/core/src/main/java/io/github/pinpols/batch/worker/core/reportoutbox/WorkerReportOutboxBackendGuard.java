package io.github.pinpols.batch.worker.core.reportoutbox;

import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
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
  private final Environment environment;

  public WorkerReportOutboxBackendGuard(
      @Qualifier("dataSource") DataSource platformDataSource,
      WorkerReportOutboxProperties properties,
      Environment environment) {
    this.guard = new StatefulBackendGuard(platformDataSource);
    this.properties = properties;
    this.environment = environment;
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
    String applicationName =
        environment.getProperty("spring.application.name", "batch-worker-unknown");
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
              StatefulBackendIdentity.database(
                  environment.getRequiredProperty("spring.datasource.url"));
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
