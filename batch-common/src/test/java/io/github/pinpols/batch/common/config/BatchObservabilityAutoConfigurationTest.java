package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.logging.OpenTelemetryLogbackBridge;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BatchObservabilityAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BatchObservabilityAutoConfiguration.class))
      .withBean(ObservationRegistry.class, ObservationRegistry::create);

  @Test
  void createsLogbackBridgeOnlyWhenOpenTelemetryIsAvailable() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ObservedAspect.class);
      assertThat(context).doesNotHaveBean(OpenTelemetryLogbackBridge.class);
    });

    contextRunner
        .withBean(OpenTelemetry.class, OpenTelemetry::noop)
        .run(context -> assertThat(context).hasSingleBean(OpenTelemetryLogbackBridge.class));
  }

  @Test
  void loadsAfterBootObservationAutoConfiguration() {
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
