package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
/** 装配 Console 的 AI 审计与调用客户端。 */
public class ConsoleAiConfiguration {

  /**
   * 按 {@code batch.console.ai.provider} 选择聊天模型。跨 Provider 故障切换仅在 {@code failover-enabled=true} 时启用；所选
   * Provider 不可用时启动失败，不静默改用其他服务。
   */
  @Bean
  @ConditionalOnProperty(prefix = "batch.console.ai", name = "enabled", havingValue = "true")
  public ConsoleAiClients consoleAiClients(
      ObjectProvider<AnthropicChatModel> anthropicChatModel,
      ObjectProvider<OpenAiChatModel> openAiChatModel,
      ConsoleAiProperties properties) {
    ConsoleAiProperties.Provider provider = properties.getProvider();
    if (provider == ConsoleAiProperties.Provider.OPENAI_COMPATIBLE) {
      return createOpenAiCompatibleClient(properties);
    }
    boolean openAiPreferred = provider == ConsoleAiProperties.Provider.OPENAI;
    ChatModel primary =
        openAiPreferred ? openAiChatModel.getIfAvailable() : anthropicChatModel.getIfAvailable();
    ChatModel fallback = null;
    if (properties.isFailoverEnabled()) {
      fallback =
          openAiPreferred ? anthropicChatModel.getIfAvailable() : openAiChatModel.getIfAvailable();
    }
    return createClients(provider, primary, fallback, properties.isFailoverEnabled());
  }

  static ConsoleAiClients createClients(
      ConsoleAiProperties.Provider preferredProvider,
      ChatModel primaryModel,
      ChatModel fallbackModel,
      boolean failoverEnabled) {
    if (EmptyChecks.isNull(primaryModel)) {
      throw new IllegalStateException(
          "configured console AI provider is unavailable; check batch.console.ai.provider and its credentials");
    }
    String primaryName = providerName(preferredProvider);
    String fallbackName =
        preferredProvider == ConsoleAiProperties.Provider.OPENAI ? "anthropic" : "openai";
    ConsoleAiClients.ProviderClient primary =
        new ConsoleAiClients.ProviderClient(primaryName, ChatClient.create(primaryModel));
    ConsoleAiClients.ProviderClient fallback =
        failoverEnabled && EmptyChecks.isNotNull(fallbackModel)
            ? new ConsoleAiClients.ProviderClient(fallbackName, ChatClient.create(fallbackModel))
            : null;
    return new ConsoleAiClients(primary, fallback);
  }

  static ConsoleAiClients createOpenAiCompatibleClient(ConsoleAiProperties properties) {
    if (properties.isFailoverEnabled()) {
      throw new IllegalStateException(
          "openai-compatible console AI provider does not support cross-provider failover");
    }
    ConsoleAiProperties.OpenaiCompatible compatible = properties.getOpenaiCompatible();
    requireCompatibleText(compatible.getBaseUrl(), "batch.console.ai.openai-compatible.base-url");
    requireCompatibleText(compatible.getApiKey(), "batch.console.ai.openai-compatible.api-key");
    requireCompatibleText(compatible.getModel(), "batch.console.ai.openai-compatible.model");

    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .baseUrl(compatible.getBaseUrl().trim())
        .apiKey(compatible.getApiKey().trim())
        .model(compatible.getModel().trim())
        .timeout(compatible.getTimeout())
        .build();
    OpenAiChatModel model = OpenAiChatModel.builder().options(options).build();
    String providerName = Texts.hasText(compatible.getProviderName())
        ? compatible.getProviderName().trim()
        : providerName(ConsoleAiProperties.Provider.OPENAI_COMPATIBLE);
    return new ConsoleAiClients(
        new ConsoleAiClients.ProviderClient(providerName, ChatClient.create(model)), null);
  }

  private static String providerName(ConsoleAiProperties.Provider provider) {
    return switch (provider) {
      case ANTHROPIC -> "anthropic";
      case OPENAI -> "openai";
      case OPENAI_COMPATIBLE -> "openai-compatible";
    };
  }

  private static void requireCompatibleText(String value, String propertyName) {
    if (!Texts.hasText(value)) {
      throw new IllegalStateException(propertyName + " must be configured for openai-compatible");
    }
  }
}
