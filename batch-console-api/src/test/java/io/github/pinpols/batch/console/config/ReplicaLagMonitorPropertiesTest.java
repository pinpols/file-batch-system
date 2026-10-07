package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@DisplayName("副本延迟监控配置绑定与参数校验")
class ReplicaLagMonitorPropertiesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(BindingConfiguration.class);

  @Test
  @DisplayName("默认采样周期和首次延迟保持不变")
  void shouldBindDefaults() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      ReplicaLagMonitorProperties properties = context.getBean(ReplicaLagMonitorProperties.class);
      assertThat(properties.getLagMonitorIntervalMillis()).isEqualTo(30_000L);
      assertThat(properties.getLagMonitorInitialDelayMillis()).isEqualTo(10_000L);
    });
  }

  @Test
  @DisplayName("采样周期必须正数, 首次延迟允许零")
  void shouldAllowZeroInitialDelay() {
    runner
        .withPropertyValues(
            "batch.console.replica.lag-monitor-interval-millis=1",
            "batch.console.replica.lag-monitor-initial-delay-millis=0")
        .run(context -> {
          assertThat(context).hasNotFailed();
          ReplicaLagMonitorProperties properties =
              context.getBean(ReplicaLagMonitorProperties.class);
          assertThat(properties.getLagMonitorIntervalMillis()).isEqualTo(1L);
          assertThat(properties.getLagMonitorInitialDelayMillis()).isZero();
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "batch.console.replica.lag-monitor-interval-millis=0",
        "batch.console.replica.lag-monitor-interval-millis=-1",
        "batch.console.replica.lag-monitor-initial-delay-millis=-1"
      })
  @DisplayName("非法周期或负延迟在配置绑定期拒绝")
  void shouldRejectInvalidValues(String property) {
    runner.withPropertyValues(property).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ReplicaLagMonitorProperties.class)
  static class BindingConfiguration {}
}
