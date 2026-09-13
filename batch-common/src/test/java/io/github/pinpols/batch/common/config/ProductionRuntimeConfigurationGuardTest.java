package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionRuntimeConfigurationGuardTest {

  @Test
  void shouldIgnoreDevelopmentProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");

    assertThatCode(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void shouldFailClosedWhenProfileIsMissing() {
    MockEnvironment environment = new MockEnvironment();

    assertThatThrownBy(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.url");
  }

  @Test
  void shouldRejectLoopbackEndpointInProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.orchestrator.base-url", "http://localhost:18082");

    assertThatThrownBy(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.orchestrator.base-url");
  }

  @Test
  void shouldRejectRandomManagementPortInProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("management.server.port", "0");

    assertThatThrownBy(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("management.server.port");
  }

  @Test
  void shouldRejectLoopbackBusinessDatabaseForProductionWorker() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("spring.application.name", "batch-worker-process");
    environment.setProperty(
        "batch.datasource.business.url", "jdbc:postgresql://127.0.0.1:5432/biz");

    assertThatThrownBy(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.datasource.business.url");
  }

  @Test
  void shouldRejectLoopbackS3ForProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.storage.backend", "s3");
    environment.setProperty("batch.storage.s3.endpoint", "http://[::1]:9000");

    assertThatThrownBy(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.storage.s3.endpoint");
  }

  @Test
  void shouldAcceptExplicitRemoteProductionEndpoints() {
    MockEnvironment environment = validProductionEnvironment();

    assertThatCode(() ->
            new ProductionRuntimeConfigurationGuard(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  private static MockEnvironment validProductionEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("spring.application.name", "batch-orchestrator");
    environment.setProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/platform");
    environment.setProperty("spring.kafka.bootstrap-servers", "kafka:9092");
    environment.setProperty("spring.data.redis.host", "valkey");
    environment.setProperty("batch.orchestrator.base-url", "http://orchestrator:18082");
    environment.setProperty("batch.storage.backend", "filesystem");
    environment.setProperty("batch.datasource.business.url", "jdbc:postgresql://postgres:5432/biz");
    environment.setProperty("management.server.port", "18082");
    return environment;
  }
}
