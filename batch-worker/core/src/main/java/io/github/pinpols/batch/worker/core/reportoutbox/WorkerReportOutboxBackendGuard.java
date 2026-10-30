package io.github.pinpols.batch.worker.core.reportoutbox;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import java.nio.file.Path;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 拒绝未标记的 Worker 回报 Outbox 启用或停用，以及 PLATFORM_PG/SQLITE 后端切换。
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
    String backend = storage.name().toLowerCase(Locale.ROOT);
    String identity =
        switch (storage) {
          case PLATFORM_PG -> {
            String datasourceUrl = infrastructureInspector.datasourceUrl();
            yield StatefulBackendIdentity.database(datasourceUrl);
          }
          case SQLITE -> {
            Path sqlitePath = properties.resolveSqlitePath();
            yield StatefulBackendIdentity.sqlite(sqlitePath);
          }
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
