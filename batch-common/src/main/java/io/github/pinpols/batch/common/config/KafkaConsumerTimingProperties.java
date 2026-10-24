package io.github.pinpols.batch.common.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Kafka consumer 时序与分配策略配置。 */
@Data
@ConfigurationProperties(prefix = "spring.kafka.consumer")
public class KafkaConsumerTimingProperties {

  private int maxPollIntervalMs = 300_000;
  private Map<String, String> properties = new LinkedHashMap<>();
}
