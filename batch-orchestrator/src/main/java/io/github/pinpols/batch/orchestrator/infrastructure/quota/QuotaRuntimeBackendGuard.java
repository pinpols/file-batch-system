package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Refuses unmarked quota runtime-store or connection-location changes at startup. */
@Slf4j
@Component
public class QuotaRuntimeBackendGuard implements ApplicationRunner, Ordered {

  static final String FEATURE_KEY = "quota-runtime-state";

  private final StatefulBackendGuard guard;
  private final QuotaProperties properties;
  private final ApplicationNameProvider applicationNameProvider;
  private final RuntimeInfrastructureInspector infrastructureInspector;

  public QuotaRuntimeBackendGuard(
      DataSource dataSource,
      QuotaProperties properties,
      ApplicationNameProvider applicationNameProvider,
      RuntimeInfrastructureInspector infrastructureInspector) {
    this.guard = new StatefulBackendGuard(dataSource);
    this.properties = properties;
    this.applicationNameProvider = applicationNameProvider;
    this.infrastructureInspector = infrastructureInspector;
  }

  @Override
  public void run(ApplicationArguments args) {
    StatefulBackendGuard.DesiredBackend desired = desiredBackend();
    StatefulBackendGuard.GuardResult result = guard.verify(desired);
    log.info(
        "quota runtime backend guard {}: backend={}, identity={}, generation={}",
        result.action(),
        desired.backend(),
        desired.backendIdentity(),
        result.generation());
  }

  StatefulBackendGuard.DesiredBackend desiredBackend() {
    String backend = properties.getRuntimeStore().trim().toLowerCase();
    String identity =
        switch (backend) {
          case QuotaRuntimeBackends.REDIS -> {
            RuntimeInfrastructureInspector.RedisCoordinates redis =
                infrastructureInspector.redisCoordinates();
            yield StatefulBackendIdentity.redis(
                redis.host(),
                redis.port(),
                redis.database(),
                redis.sentinelMaster(),
                redis.sentinelNodes());
          }
          case QuotaRuntimeBackends.DATABASE ->
            StatefulBackendIdentity.database(infrastructureInspector.datasourceUrl());
          default ->
            throw new IllegalStateException(
                "unsupported batch.quota.runtime-store: " + properties.getRuntimeStore());
        };
    return new StatefulBackendGuard.DesiredBackend(
        FEATURE_KEY,
        backend,
        identity,
        properties.getBackendGuard().getCutoverId(),
        applicationNameProvider.name("batch-orchestrator"));
  }

  @Override
  public int getOrder() {
    return HIGHEST_PRECEDENCE + 20;
  }
}
