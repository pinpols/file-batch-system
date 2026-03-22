package com.example.batch.console.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsoleAiConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "batch.console.ai", name = "enabled", havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public ChatClient consoleChatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
