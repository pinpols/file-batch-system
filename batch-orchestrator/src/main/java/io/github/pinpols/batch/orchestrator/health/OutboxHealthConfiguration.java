package io.github.pinpols.batch.orchestrator.health;

import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OutboxLagHealthProperties.class)
public class OutboxHealthConfiguration {

  @Bean
  @ConditionalOnProperty(
      name = "batch.orchestrator.health.outbox.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public OutboxLagHealthIndicator outboxLagHealthIndicator(
      OutboxEventMapper mapper, OutboxLagHealthProperties properties) {
    return new OutboxLagHealthIndicator(mapper, properties);
  }
}
