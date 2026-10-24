package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker Kafka 订阅应用部署命名空间")
class TaskConsumerRoutingPolicyNamespaceTest {

  @Test
  @DisplayName("命名空间前缀覆盖基础与 node-direct 主题但不扩大正则匹配范围")
  void shouldNamespaceWorkerTopicPattern() {
    WorkerConfiguration configuration = mock(WorkerConfiguration.class);
    when(configuration.workerType()).thenReturn("IMPORT");
    when(configuration.workerCode()).thenReturn("worker-a");
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("run-a");
    Pattern pattern =
        Pattern.compile(TaskConsumerRoutingPolicy.topicPattern(configuration, null, namespace));

    assertThat(pattern.matcher("run-a.batch.task.dispatch.import").matches()).isTrue();
    assertThat(pattern.matcher("run-a.batch.task.dispatch.import.tenant-1").matches())
        .isTrue();
    assertThat(pattern.matcher("run-a.batch.task.dispatch.import.node.worker-a").matches())
        .isTrue();
    assertThat(pattern.matcher("batch.task.dispatch.import").matches()).isFalse();
    assertThat(pattern.matcher("run-a.batch.task.dispatch.import.node.worker-b").matches())
        .isFalse();
  }

  @Test
  @DisplayName("命名空间隔离 Worker 消费者组")
  void shouldNamespaceWorkerConsumerGroup() {
    BatchKafkaTopicNamespace namespace = new BatchKafkaTopicNamespace("run-a");

    assertThat(namespace.consumerGroup("batch-worker-import"))
        .isEqualTo("run-a.batch-worker-import");
  }
}
