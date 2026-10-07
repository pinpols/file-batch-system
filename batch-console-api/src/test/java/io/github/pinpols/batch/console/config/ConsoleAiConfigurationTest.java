package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

@DisplayName("控制台人工智能客户端装配: 供应商选择,备用链路与图片输入开关组合")
class ConsoleAiConfigurationTest {

  @Test
  @DisplayName("未开启跨供应商故障转移时,只注册主客户端且不注册备用客户端")
  void shouldHaveNoFallback_whenCrossProviderFailoverIsDisabled() {
    ChatModel primary = mock(ChatModel.class);
    ChatModel alternate = mock(ChatModel.class);
    ConsoleAiProperties properties = new ConsoleAiProperties();

    ConsoleAiClients clients = ConsoleAiConfiguration.createClients(
        properties.getProvider(), primary, alternate, properties.isFailoverEnabled());

    assertThat(clients.primary().provider()).isEqualTo("anthropic");
    assertThat(clients.fallback()).isNull();
  }

  @Test
  @DisplayName("开启跨供应商故障转移时,备用客户端由另一供应商提供")
  void shouldCreateFallback_whenCrossProviderFailoverIsEnabled() {
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
  @DisplayName("被选中的供应商没有可用客户端时,直接失败而不静默改用备用供应商")
  void shouldThrow_whenSelectedProviderClientIsMissing() {
    ChatModel alternate = mock(ChatModel.class);

    assertThatThrownBy(() -> ConsoleAiConfiguration.createClients(
            ConsoleAiProperties.Provider.ANTHROPIC, null, alternate, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configured console AI provider is unavailable");
  }

  @Test
  @DisplayName("创建兼容协议客户端时,采用显式配置的供应商名且不注册备用客户端")
  void shouldUseExplicitProviderName_whenOpenAiCompatibleClientIsCreated() {
    ConsoleAiProperties properties = openAiCompatibleProperties();
    properties.getOpenaiCompatible().setProviderName("deepseek");

    ConsoleAiClients clients = ConsoleAiConfiguration.createOpenAiCompatibleClient(properties);

    assertThat(clients.primary().provider()).isEqualTo("deepseek");
    assertThat(clients.primary().client()).isNotNull();
    assertThat(clients.fallback()).isNull();
  }

  @Test
  @DisplayName("图片输入需总开关与兼容供应商开关同时开启,任一关闭即视为不支持")
  void shouldEnableImageInput_whenBothFeatureAndCompatibleSwitchesAreOn() {
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
  @DisplayName("兼容供应商端点缺少模型名时,创建客户端失败并指明缺失配置项")
  void shouldFailCreation_whenCompatibleEndpointModelIsBlank() {
    ConsoleAiProperties properties = openAiCompatibleProperties();
    properties.getOpenaiCompatible().setModel("");

    assertThatThrownBy(() -> ConsoleAiConfiguration.createOpenAiCompatibleClient(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.console.ai.openai-compatible.model");
  }

  @Test
  @DisplayName("兼容供应商不支持跨供应商故障转移,开启该开关后拒绝创建")
  void shouldRejectFailover_whenCompatibleProviderIsUsed() {
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
