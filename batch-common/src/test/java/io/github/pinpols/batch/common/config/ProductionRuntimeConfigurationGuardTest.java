package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("生产运行期配置守卫:开发环境放行、必需地址缺失与回环地址拒绝,以及远端地址的通过场景")
class ProductionRuntimeConfigurationGuardTest {

  @Test
  @DisplayName("本地环境跳过生产校验,初始化完成后不抛任何异常")
  void shouldIgnoreDevelopmentProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");

    assertThatCode(() -> guard(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("未配置激活环境时按生产处理,缺少平台数据源地址即失败以保证失败安全")
  void shouldFailClosedWhenProfileIsMissing() {
    MockEnvironment environment = new MockEnvironment();

    assertFailsClosed(environment, "spring.datasource.url");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  @DisplayName("生产环境 Kafka namespace 为空或空白时启动校验失败")
  void shouldRequireKafkaNamespaceInProduction(String namespace) {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.kafka.namespace", namespace);

    assertFailsClosed(environment, "batch.kafka.namespace");
  }

  @Test
  @DisplayName("生产环境调度端地址指向本机回环地址时启动校验失败并指出该配置项")
  void shouldRejectLoopbackEndpointInProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.orchestrator.base-url", "http://localhost:18082");

    assertFailsClosed(environment, "batch.orchestrator.base-url");
  }

  @Test
  @DisplayName("生产环境管理端口设为随机端口时启动校验失败并指出该配置项")
  void shouldRejectRandomManagementPortInProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("management.server.port", "0");

    assertFailsClosed(environment, "management.server.port");
  }

  @Test
  @DisplayName("生产数据处理进程的业务库地址指向回环地址时启动校验失败并指出该配置项")
  void shouldRejectLoopbackBusinessDatabaseForProductionWorker() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("spring.application.name", "batch-worker-process");
    environment.setProperty(
        "batch.datasource.business.url", "jdbc:postgresql://127.0.0.1:5432/biz");

    assertFailsClosed(environment, "batch.datasource.business.url");
  }

  @Test
  @DisplayName("生产环境选择对象存储且服务端点指向回环地址时启动校验失败并指出该配置项")
  void shouldRejectLoopbackS3ForProduction() {
    MockEnvironment environment = validProductionEnvironment();
    environment.setProperty("batch.storage.backend", "s3");
    environment.setProperty("batch.storage.s3.endpoint", "http://[::1]:9000");

    assertFailsClosed(environment, "batch.storage.s3.endpoint");
  }

  @Test
  @DisplayName("生产环境各端点均配置为显式远端地址时启动校验通过")
  void shouldAcceptExplicitRemoteProductionEndpoints() {
    MockEnvironment environment = validProductionEnvironment();

    assertThatCode(() -> guard(environment).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("生产上下文共享已绑定的 Kafka 命名空间配置并成功启动")
  void shouldBindKafkaNamespace_whenProductionContextStarts() {
    new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .withConfiguration(AutoConfigurations.of(
            ProductionRuntimeConfigurationGuard.class, BatchKafkaNamespaceAutoConfiguration.class))
        .withPropertyValues(
            "spring.application.name=batch-orchestrator",
            "spring.datasource.url=jdbc:postgresql://postgres:5432/platform",
            "spring.kafka.bootstrap-servers=kafka:9092",
            "spring.data.redis.host=valkey",
            "batch.orchestrator.base-url=http://orchestrator:18082",
            "batch.kafka.namespace=test-deployment",
            "batch.storage.backend=filesystem",
            "management.server.port=18082")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(KafkaNamespaceProperties.class);
          assertThat(context.getBean(KafkaNamespaceProperties.class).getNamespace())
              .isEqualTo("test-deployment");
        });
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
    KafkaNamespaceProperties kafkaNamespace = Binder.get(environment)
        .bind("batch.kafka", Bindable.of(KafkaNamespaceProperties.class))
        .orElseGet(KafkaNamespaceProperties::new);
    return new ProductionRuntimeConfigurationGuard(
        environment, storage, readReplica, kafkaNamespace);
  }

  private static MockEnvironment validProductionEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("spring.application.name", "batch-orchestrator");
    environment.setProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/platform");
    environment.setProperty("spring.kafka.bootstrap-servers", "kafka:9092");
    environment.setProperty("batch.kafka.namespace", "test-deployment");
    environment.setProperty("spring.data.redis.host", "valkey");
    environment.setProperty("batch.orchestrator.base-url", "http://orchestrator:18082");
    environment.setProperty("batch.storage.backend", "filesystem");
    environment.setProperty("batch.datasource.business.url", "jdbc:postgresql://postgres:5432/biz");
    environment.setProperty("management.server.port", "18082");
    return environment;
  }
}
