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

  @Test
  void createsOpenAiCompatibleClientWithExplicitProviderName() {
    ConsoleAiProperties properties = openAiCompatibleProperties();
    properties.getOpenaiCompatible().setProviderName("deepseek");

    ConsoleAiClients clients = ConsoleAiConfiguration.createOpenAiCompatibleClient(properties);

    assertThat(clients.primary().provider()).isEqualTo("deepseek");
    assertThat(clients.primary().client()).isNotNull();
    assertThat(clients.fallback()).isNull();
  }

  @Test
  void compatibleImageInputNeedsBothFeatureAndModelSwitches() {
    ConsoleAiProperties properties = openAiCompatibleProperties();
    properties.getOpenaiCompatible().setImageInputEnabled(true);
    assertThat(ConsoleAiConfiguration.createOpenAiCompatibleClient(properties)
            .primary()
            .imageInput())
        .isFalse();

    properties.setImageInputEnabled(true);
    assertThat(ConsoleAiConfiguration.createOpenAiCompatibleClient(properties)
            .primary()
            .imageInput())
        .isTrue();

    properties.getOpenaiCompatible().setImageInputEnabled(false);
    assertThat(ConsoleAiConfiguration.createOpenAiCompatibleClient(properties)
            .primary()
            .imageInput())
        .isFalse();
  }

  @Test
  void openAiCompatibleProviderFailsWhenEndpointIsIncomplete() {
    ConsoleAiProperties properties = openAiCompatibleProperties();
    properties.getOpenaiCompatible().setModel("");

    assertThatThrownBy(() -> ConsoleAiConfiguration.createOpenAiCompatibleClient(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.console.ai.openai-compatible.model");
  }

  @Test
  void openAiCompatibleProviderDoesNotAllowCrossProviderFailover() {
    ConsoleAiProperties properties = openAiCompatibleProperties();
    properties.setFailoverEnabled(true);

    assertThatThrownBy(() -> ConsoleAiConfiguration.createOpenAiCompatibleClient(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not support cross-provider failover");
  }

  private static ConsoleAiProperties openAiCompatibleProperties() {
    ConsoleAiProperties properties = new ConsoleAiProperties();
    properties.setProvider(ConsoleAiProperties.Provider.OPENAI_COMPATIBLE);
    properties.getOpenaiCompatible().setBaseUrl("https://api.deepseek.com");
    properties.getOpenaiCompatible().setApiKey("test-key");
    properties.getOpenaiCompatible().setModel("deepseek-chat");
    return properties;
  }
}
