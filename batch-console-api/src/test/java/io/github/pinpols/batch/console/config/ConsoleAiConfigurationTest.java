package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class ConsoleAiConfigurationTest {

  @Test
  void doesNotCreateFallbackUnlessCrossProviderFailoverIsEnabled() {
    ChatModel primary = mock(ChatModel.class);
    ChatModel alternate = mock(ChatModel.class);
    ConsoleAiProperties properties = new ConsoleAiProperties();

    ConsoleAiClients clients = ConsoleAiConfiguration.createClients(
        properties.getProvider(), primary, alternate, properties.isFailoverEnabled());

    assertThat(clients.primary().provider()).isEqualTo("anthropic");
    assertThat(clients.fallback()).isNull();
  }

  @Test
  void createsFallbackOnlyWhenCrossProviderFailoverIsEnabled() {
    ChatModel primary = mock(ChatModel.class);
    ChatModel alternate = mock(ChatModel.class);
    ConsoleAiProperties properties = new ConsoleAiProperties();
    properties.setFailoverEnabled(true);

    ConsoleAiClients clients = ConsoleAiConfiguration.createClients(
        properties.getProvider(), primary, alternate, properties.isFailoverEnabled());

    assertThat(clients.primary().provider()).isEqualTo("anthropic");
    assertThat(clients.fallback().provider()).isEqualTo("openai");
  }

  @Test
  void doesNotSilentlyPromoteAlternateWhenSelectedProviderIsUnavailable() {
    ChatModel alternate = mock(ChatModel.class);

    assertThatThrownBy(() -> ConsoleAiConfiguration.createClients(
            ConsoleAiProperties.Provider.ANTHROPIC, null, alternate, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configured console AI provider is unavailable");
  }
}
