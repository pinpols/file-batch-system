package io.github.pinpols.batch.worker.core.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Kafka 消费者容器工厂: 批量领取开关下的并发容量校验")
class KafkaConsumerConfigurationTest {

  @Test
  @DisplayName("批量领取开启时, 监听并发与单次拉取条数相乘超过任务并发许可则拒绝创建容器并提示实际所需许可")
  void shouldRejectFactory_whenPollCapacityExceedsTaskPermits() {
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
    ReflectionTestUtils.setField(configuration, "listenerConcurrency", 4);
    ReflectionTestUtils.setField(configuration, "maxPollRecords", 8);

    assertThatThrownBy(() -> configuration.batchKafkaListenerContainerFactory(
            consumerFactory(),
            ObservationRegistry.NOOP,
            batchClaimProperties(true),
            concurrencyProperties(8)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("listener-concurrency=4")
        .hasMessageContaining("max-poll-records=8")
        .hasMessageContaining("max-concurrent-tasks=8")
        .hasMessageContaining("required-permits=32");
  }

  @Test
  @DisplayName("批量领取开启且所需许可与任务并发上限相等时, 容器工厂正常创建")
  void shouldAllowFactory_whenPollCapacityEqualsTaskPermits() {
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
    ReflectionTestUtils.setField(configuration, "maxPollRecords", 8);
    ReflectionTestUtils.setField(configuration, "listenerConcurrency", 4);

    assertThatCode(() -> configuration.batchKafkaListenerContainerFactory(
            consumerFactory(),
            ObservationRegistry.NOOP,
            batchClaimProperties(true),
            concurrencyProperties(32)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("批量领取关闭时, 即使监听并发与拉取条数不匹配也不做容量校验")
  void shouldSkipCapacityCheck_whenBatchClaimDisabled() {
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
    ReflectionTestUtils.setField(configuration, "maxPollRecords", 10);
    ReflectionTestUtils.setField(configuration, "listenerConcurrency", 1);

    assertThatCode(() -> configuration.batchKafkaListenerContainerFactory(
            consumerFactory(),
            ObservationRegistry.NOOP,
            batchClaimProperties(false),
            concurrencyProperties(8)))
        .doesNotThrowAnyException();
  }

  private static WorkerBatchClaimProperties batchClaimProperties(boolean enabled) {
    WorkerBatchClaimProperties properties = new WorkerBatchClaimProperties();
    properties.setEnabled(enabled);
    return properties;
  }

  private static WorkerConcurrencyProperties concurrencyProperties(int maxConcurrentTasks) {
    WorkerConcurrencyProperties properties = new WorkerConcurrencyProperties();
    properties.setMaxConcurrentTasks(maxConcurrentTasks);
    return properties;
  }

  @SuppressWarnings("unchecked")
  private ConsumerFactory<String, String> consumerFactory() {
    return mock(ConsumerFactory.class);
  }
}
