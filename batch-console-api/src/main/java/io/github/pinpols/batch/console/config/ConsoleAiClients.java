package io.github.pinpols.batch.console.config;

import org.springframework.ai.chat.client.ChatClient;

/** 已配置的主模型与可选故障切换模型。 */
public record ConsoleAiClients(ProviderClient primary, ProviderClient fallback) {

  public record ProviderClient(String provider, ChatClient client) {}
}
