package io.github.pinpols.batch.common.kafka;

import java.util.regex.Pattern;

/** Applies one deployment namespace consistently to Kafka topic and consumer-group names. */
public final class BatchKafkaTopicNamespace {

  private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

  private final String namespace;

  public BatchKafkaTopicNamespace(String namespace) {
    this.namespace = namespace == null ? "" : namespace.trim();
    if (!this.namespace.isEmpty() && !VALID_NAMESPACE.matcher(this.namespace).matches()) {
      throw new IllegalArgumentException(
          "batch.kafka.namespace must match [a-z0-9][a-z0-9_-]{0,47}");
    }
  }

  public String topic(String topicName) {
    return apply(topicName);
  }

  public String consumerGroup(String groupId) {
    return apply(groupId);
  }

  private String apply(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Kafka name must not be blank");
    }
    return namespace.isEmpty() ? name : namespace + "." + name;
  }
}
