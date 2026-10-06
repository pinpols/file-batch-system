package io.github.pinpols.batch.console.domain.observability.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.config.ConsoleInstanceIdProperties;
import org.junit.jupiter.api.Test;

class ConsoleRealtimeInstanceIdProviderTest {

  @Test
  void shouldUseExplicitInstanceIdWhenConfigured() {
    ConsoleInstanceIdProperties properties = new ConsoleInstanceIdProperties();
    properties.setInstanceId("console-a");

    ConsoleRealtimeInstanceIdProvider provider = new ConsoleRealtimeInstanceIdProvider(properties);

    assertThat(provider.instanceId()).isEqualTo("console-a");
  }

  @Test
  void shouldGenerateUuidWhenInstanceIdMissing() {
    ConsoleRealtimeInstanceIdProvider provider =
        new ConsoleRealtimeInstanceIdProvider(new ConsoleInstanceIdProperties());

    assertThat(provider.instanceId()).isNotBlank();
    assertThat(provider.instanceId()).contains("-");
  }
}
