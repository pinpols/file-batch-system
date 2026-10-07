package io.github.pinpols.batch.console.domain.observability.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.config.ConsoleInstanceIdProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("实时实例标识提供者: 显式配置优先与缺省时的自动生成")
class ConsoleRealtimeInstanceIdProviderTest {

  @Test
  @DisplayName("配置了实例标识时直接采用该值")
  void shouldUseExplicitInstanceIdWhenConfigured() {
    ConsoleInstanceIdProperties properties = new ConsoleInstanceIdProperties();
    properties.setInstanceId("console-a");

    ConsoleRealtimeInstanceIdProvider provider = new ConsoleRealtimeInstanceIdProvider(properties);

    assertThat(provider.instanceId()).isEqualTo("console-a");
  }

  @Test
  @DisplayName("未配置实例标识时自动生成带连字符的唯一标识")
  void shouldGenerateUuidWhenInstanceIdMissing() {
    ConsoleRealtimeInstanceIdProvider provider =
        new ConsoleRealtimeInstanceIdProvider(new ConsoleInstanceIdProperties());

    assertThat(provider.instanceId()).isNotBlank();
    assertThat(provider.instanceId()).contains("-");
  }
}
