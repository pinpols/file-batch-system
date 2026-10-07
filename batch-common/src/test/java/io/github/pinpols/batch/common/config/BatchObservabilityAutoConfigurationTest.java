package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.logging.OpenTelemetryLogbackBridge;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("可观测性自动配置:日志桥接的装配前提,以及与框架自带可观测自动配置并存时的加载顺序")
class BatchObservabilityAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BatchObservabilityAutoConfiguration.class))
      .withBean(ObservationRegistry.class, ObservationRegistry::create);

  @Test
  @DisplayName("无论是否具备链路追踪实现都注册被观测切面,仅在追踪组件就绪时才注册日志桥接")
  void shouldCreateLogbackBridge_whenTracingImplementationPresent() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ObservedAspect.class);
      assertThat(context).doesNotHaveBean(OpenTelemetryLogbackBridge.class);
    });

    contextRunner
        .withBean(OpenTelemetry.class, OpenTelemetry::noop)
        .run(context -> assertThat(context).hasSingleBean(OpenTelemetryLogbackBridge.class));
  }

  @Test
  @DisplayName("与框架自带可观测自动配置同时启用时,注册表、切面与日志桥接三者均可正常装配")
  void shouldLoadAfterFrameworkObservationAutoConfiguration() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ObservationAutoConfiguration.class, BatchObservabilityAutoConfiguration.class))
        .withBean(OpenTelemetry.class, OpenTelemetry::noop)
        .run(context -> {
          assertThat(context).hasSingleBean(ObservationRegistry.class);
          assertThat(context).hasSingleBean(ObservedAspect.class);
          assertThat(context).hasSingleBean(OpenTelemetryLogbackBridge.class);
        });
  }
}
