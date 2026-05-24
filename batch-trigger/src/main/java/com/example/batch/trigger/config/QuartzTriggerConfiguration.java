package com.example.batch.trigger.config;

import com.example.batch.common.config.BatchSecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({
  OrchestratorClientProperties.class,
  TriggerRuntimeProperties.class,
  TriggerOutboxRelayProperties.class
})
public class QuartzTriggerConfiguration {

  @Bean
  public RestClient orchestratorRestClient(
      RestClient.Builder builder,
      OrchestratorClientProperties properties,
      BatchSecurityProperties securityProperties) {
    return builder
        .baseUrl(properties.getBaseUrl())
        .defaultHeader("X-Internal-Secret", securityProperties.getInternalSecret())
        .build();
  }
}
