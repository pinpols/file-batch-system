package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class ProductionRuntimeConfigurationGuardTest {

  @Test
  void shouldIgnoreDevelopmentProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");

    assertThatCode(() -> guard(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void shouldFailClosedWhenProfileIsMissing() {
    MockEnvironment environment = new MockEnvironment();

    assertFailsClosed(environment, "spring.datasource.url");
  }

  @Test
  void shouldRejectLoopbackEndpointInProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.orchestrator.base-url", "http://localhost:18082");

    assertFailsClosed(environment, "batch.orchestrator.base-url");
  }

  @Test
  void shouldRejectRandomManagementPortInProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("management.server.port", "0");

    assertFailsClosed(environment, "management.server.port");
  }

  @Test
  void shouldRejectLoopbackBusinessDatabaseForProductionWorker() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("spring.application.name", "batch-worker-process");
    environment.setProperty(
        "batch.datasource.business.url", "jdbc:postgresql://127.0.0.1:5432/biz");

    assertFailsClosed(environment, "batch.datasource.business.url");
  }

  @Test
  void shouldRejectLoopbackS3ForProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.storage.backend", "s3");
    environment.setProperty("batch.storage.s3.endpoint", "http://[::1]:9000");

    assertFailsClosed(environment, "batch.storage.s3.endpoint");
  }

  @Test
  void shouldAcceptExplicitRemoteProductionEndpoints() {
    MockEnvironment environment = validProductionEnvironment();

    assertThatCode(() -> guard(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  /**
   * 断言生产配置守卫在给定环境下 fail-closed。
   *
   * <p>不直接写 {@code assertThatThrownBy(() -> guard(env).afterSingletonsInstantiated())}：lambda 里有
   * 两个可能抛异常的调用时，断言无法定位失败发生在哪一步（java:S5778），故把构造守卫的调用提到 lambda
   * 之外，lambda 内只保留一个可能抛异常的调用。
   */
  private static void assertFailsClosed(MockEnvironment environment, String messageFragment) {
    ProductionRuntimeConfigurationGuard configurationGuard = guard(environment);
    assertThatThrownBy(configurationGuard::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(messageFragment);
  }

  private static ProductionRuntimeConfigurationGuard guard(MockEnvironment environment) {
    StorageBackendProperties storage = Binder.get(environment)
        .bind("batch.storage", Bindable.of(StorageBackendProperties.class))
        .orElseGet(StorageBackendProperties::new);
    ConsoleReadReplicaProperties readReplica = Binder.get(environment)
        .bind("batch.console.read-replica", Bindable.of(ConsoleReadReplicaProperties.class))
        .orElseGet(ConsoleReadReplicaProperties::new);
    return new ProductionRuntimeConfigurationGuard(environment, storage, readReplica);
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
