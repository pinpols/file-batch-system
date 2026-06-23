package io.github.pinpols.batch.console.domain.observability.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConsoleRealtimeInstanceIdProviderTest {

  @Test
  void shouldUseExplicitInstanceIdWhenConfigured() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("batch.console.instance-id", "console-a");

    ConsoleRealtimeInstanceIdProvider provider = new ConsoleRealtimeInstanceIdProvider(environment);

    assertThat(provider.instanceId()).isEqualTo("console-a");
  }

  @Test
  void shouldGenerateUuidWhenInstanceIdMissing() {
    ConsoleRealtimeInstanceIdProvider provider =
        new ConsoleRealtimeInstanceIdProvider(new MockEnvironment());

    assertThat(provider.instanceId()).isNotBlank();
    assertThat(provider.instanceId()).contains("-");
  }
}
