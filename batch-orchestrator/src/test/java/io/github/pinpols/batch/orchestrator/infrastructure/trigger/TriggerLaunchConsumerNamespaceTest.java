package io.github.pinpols.batch.orchestrator.infrastructure.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.config.TriggerConsumerProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Trigger 消费主题与消费组隔离")
class TriggerLaunchConsumerNamespaceTest {

  @Test
  @DisplayName("主题与消费组应用部署命名空间")
  void shouldNamespaceTopicAndConsumerGroup() {
    TriggerConsumerProperties properties = new TriggerConsumerProperties();
    TriggerLaunchConsumer consumer = new TriggerLaunchConsumer(
        mock(LaunchApplicationService.class),
        mock(OrchestratorGracefulShutdown.class),
        new SimpleMeterRegistry(),
        properties,
        new BatchKafkaTopicNamespace("workflow-east-1"));

    assertThat(consumer.topicName()).isEqualTo("workflow-east-1." + BatchTopics.TRIGGER_LAUNCH_V1);
    assertThat(consumer.consumerGroupId()).isEqualTo("workflow-east-1." + properties.getGroupId());
  }
}
