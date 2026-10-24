package io.github.pinpols.batch.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchKafkaTopicNamespaceTest {

  @Test
  @DisplayName("empty namespace preserves the existing local topic and group names")
  void shouldPreserveNamesWhenNamespaceIsEmpty() {
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("");

    assertThat(namespace.topic("batch.task.dispatch.import"))
        .isEqualTo("batch.task.dispatch.import");
    assertThat(namespace.consumerGroup("batch-worker-import"))
        .isEqualTo("batch-worker-import");
  }

  @Test
  @DisplayName("deployment namespace separates both topics and consumer groups")
  void shouldPrefixTopicAndConsumerGroup() {
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("workflow-east-1");

    assertThat(namespace.topic("batch.trigger.launch.v1"))
        .isEqualTo("workflow-east-1.batch.trigger.launch.v1");
    assertThat(namespace.consumerGroup("orchestrator-trigger-launch"))
        .isEqualTo("workflow-east-1.orchestrator-trigger-launch");
  }

  @Test
  @DisplayName("invalid namespace or blank Kafka names fail closed")
  void shouldRejectInvalidNamespaceAndName() {
    assertThatThrownBy(() -> new BatchKafkaTopicNamespace("Legacy.Env"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batch.kafka.namespace");

    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("workflow-east-1");
    assertThatThrownBy(() -> namespace.topic(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Kafka name");
  }
}
