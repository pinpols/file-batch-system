package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Namespaces shared Kafka topics and consumer groups for one deployment. */
@Data
@ConfigurationProperties(prefix = "batch.kafka")
public class KafkaNamespaceProperties {

  /** Lowercase deployment slug; blank is allowed only outside production. */
  private String namespace = "";
}
