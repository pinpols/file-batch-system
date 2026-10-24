package io.github.pinpols.batch.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Kafka 主题与消费组的命名空间隔离")
class BatchKafkaTopicNamespaceTest {

  @Test
  @DisplayName("命名空间为空时保留原名称")
  void shouldPreserveNamesWhenNamespaceIsEmpty() {
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("");

    assertThat(namespace.topic("batch.task.dispatch.import"))
        .isEqualTo("batch.task.dispatch.import");
    assertThat(namespace.consumerGroup("batch-worker-import")).isEqualTo("batch-worker-import");
  }

  @Test
  @DisplayName("主题和消费组应用同一部署前缀")
  void shouldPrefixTopicAndConsumerGroup() {
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("workflow-east-1");

    assertThat(namespace.topic("batch.trigger.launch.v1"))
        .isEqualTo("workflow-east-1.batch.trigger.launch.v1");
    assertThat(namespace.consumerGroup("orchestrator-trigger-launch"))
        .isEqualTo("workflow-east-1.orchestrator-trigger-launch");
  }

  @Test
  @DisplayName("拒绝非法命名空间和名称")
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
