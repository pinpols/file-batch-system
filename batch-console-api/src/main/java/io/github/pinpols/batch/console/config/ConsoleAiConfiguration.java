package io.github.pinpols.batch.console.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsoleAiConfiguration {

  /**
   * 按 {@code batch.console.ai.provider} 选择聊天模型:默认 anthropic(Claude),可选 openai。 两个 starter 都在
   * classpath 时会有两个 ChatModel bean,这里显式按 provider 取,避免注入歧义; 选中的 provider 不可用时回退另一个,都没有则启动报错(开启 AI
   * 却没配模型 = 配置错误)。
   */
  @Bean
  @ConditionalOnProperty(prefix = "batch.console.ai", name = "enabled", havingValue = "true")
  public ChatClient consoleChatClient(
      ObjectProvider<AnthropicChatModel> anthropicChatModel,
      ObjectProvider<OpenAiChatModel> openAiChatModel,
      ConsoleAiProperties properties) {
    boolean openAiPreferred = "openai".equalsIgnoreCase(properties.getProvider());
    ChatModel primary =
        openAiPreferred ? openAiChatModel.getIfAvailable() : anthropicChatModel.getIfAvailable();
    if (primary != null) {
      return ChatClient.create(primary);
    }
    ChatModel fallback =
        openAiPreferred ? anthropicChatModel.getIfAvailable() : openAiChatModel.getIfAvailable();
    if (fallback != null) {
      return ChatClient.create(fallback);
    }
    throw new IllegalStateException(
        "console AI enabled but no chat model available; configure anthropic or openai api-key");
  }
}
