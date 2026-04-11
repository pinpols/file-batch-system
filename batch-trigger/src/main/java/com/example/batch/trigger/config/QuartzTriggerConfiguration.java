package com.example.batch.trigger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({OrchestratorClientProperties.class, TriggerRuntimeProperties.class})
public class QuartzTriggerConfiguration {

    @Bean
    public RestClient orchestratorRestClient(
            RestClient.Builder builder, OrchestratorClientProperties properties) {
        return builder.baseUrl(properties.getBaseUrl()).build();
    }
}
