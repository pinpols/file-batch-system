package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import io.github.pinpols.batch.common.stateful.StatefulBackendGuard;
import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Refuses unmarked quota runtime-store or connection-location changes at startup. */
@Slf4j
@Component
public class QuotaRuntimeBackendGuard implements ApplicationRunner, Ordered {

  static final String FEATURE_KEY = "quota-runtime-state";

  private final StatefulBackendGuard guard;
  private final QuotaProperties properties;
  private final Environment environment;

  public QuotaRuntimeBackendGuard(
      DataSource dataSource, QuotaProperties properties, Environment environment) {
    this.guard = new StatefulBackendGuard(dataSource);
    this.properties = properties;
    this.environment = environment;
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
          case "redis" ->
              StatefulBackendIdentity.redis(
                  environment.getProperty("spring.data.redis.host", "localhost"),
                  environment.getProperty("spring.data.redis.port", Integer.class, 6379),
                  environment.getProperty("spring.data.redis.database", Integer.class, 0),
                  environment.getProperty("spring.data.redis.sentinel.master"),
                  environment.getProperty("spring.data.redis.sentinel.nodes"));
          case "database" ->
              StatefulBackendIdentity.database(
                  environment.getRequiredProperty("spring.datasource.url"));
          default ->
              throw new IllegalStateException(
                  "unsupported batch.quota.runtime-store: " + properties.getRuntimeStore());
        };
    return new StatefulBackendGuard.DesiredBackend(
        FEATURE_KEY,
        backend,
        identity,
        properties.getBackendGuard().getCutoverId(),
        environment.getProperty("spring.application.name", "batch-orchestrator"));
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }
}
