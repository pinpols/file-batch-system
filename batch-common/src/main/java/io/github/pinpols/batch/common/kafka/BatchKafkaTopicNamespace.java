package io.github.pinpols.batch.common.kafka;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Objects;
import java.util.regex.Pattern;

/** 对 Kafka topic 和消费组名称一致应用部署命名空间。 */
public final class BatchKafkaTopicNamespace {

  private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

  private final String namespace;

  public BatchKafkaTopicNamespace(String namespace) {
    this.namespace = EmptyChecks.isNull(namespace) ? "" : namespace.trim();
    boolean namespaceIsInvalid = !EmptyChecks.isEmpty(Objects.requireNonNull(this.namespace))
        && !VALID_NAMESPACE.matcher(this.namespace).matches();
    if (namespaceIsInvalid) {
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
    if (EmptyChecks.isBlank(name)) {
      throw new IllegalArgumentException("Kafka name must not be blank");
    }
    return EmptyChecks.isEmpty(Objects.requireNonNull(namespace)) ? name : namespace + "." + name;
  }
}
