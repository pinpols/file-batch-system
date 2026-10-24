package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Kafka broker 连接配置。 */
@Data
@ConfigurationProperties(prefix = "spring.kafka")
public class KafkaConnectionProperties {

  private String bootstrapServers;
}
