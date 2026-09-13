package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.env.Environment;

/** 阻止生产环境静默使用本地开发地址或不可发现的管理端口。 */
@AutoConfiguration
public class ProductionRuntimeConfigurationGuard implements SmartInitializingSingleton {

  private static final Pattern LOOPBACK_ENDPOINT =
      Pattern.compile("(^|[/:@,])(?:localhost|127(?:\\.[0-9]{1,3}){3}|\\[::1]|::1)(?=[:/? ,]|$)");
  private static final List<String> REQUIRED_ENDPOINTS = List.of(
      "spring.datasource.url",
      "spring.kafka.bootstrap-servers",
      "spring.data.redis.host",
      "batch.orchestrator.base-url");
  private static final List<String> OPTIONAL_ENDPOINTS = List.of(
      "batch.console.trigger.base-url",
      "batch.console.orchestrator.base-url",
      "batch.console.atomic-worker.base-url",
      "batch.console.read-replica.primary.url");

  private final Environment environment;

  public ProductionRuntimeConfigurationGuard(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!BatchProfileSupport.isProductionProfile(environment)) {
      return;
    }

    REQUIRED_ENDPOINTS.forEach(this::requireRemoteEndpoint);
    OPTIONAL_ENDPOINTS.stream()
        .filter(environment::containsProperty)
        .forEach(this::requireRemoteEndpoint);

    if ("s3".equalsIgnoreCase(environment.getProperty("batch.storage.backend", "s3"))) {
      requireRemoteEndpoint("batch.storage.s3.endpoint");
    }
    if (environment.getProperty("spring.application.name", "").startsWith("batch-worker-")) {
      requireRemoteEndpoint("batch.datasource.business.url");
    }
    if (environment.getProperty("batch.console.read-replica.enabled", Boolean.class, false)) {
      requireRemoteEndpoint("batch.console.read-replica.replica.url");
    }

    Integer managementPort = environment.getProperty("management.server.port", Integer.class);
    if (EmptyChecks.isNull(managementPort) || managementPort <= 0) {
      throw new IllegalStateException(
          "FATAL: production management.server.port must be explicitly configured to a positive, discoverable port");
    }
  }

  private void requireRemoteEndpoint(String key) {
    String value = environment.getProperty(key);
    if (EmptyChecks.isBlank(value)) {
      throw new IllegalStateException("FATAL: production endpoint is not configured: " + key);
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (LOOPBACK_ENDPOINT.matcher(normalized).find()) {
      throw new IllegalStateException(
          "FATAL: production endpoint still uses a loopback development address: " + key);
    }
  }
}
