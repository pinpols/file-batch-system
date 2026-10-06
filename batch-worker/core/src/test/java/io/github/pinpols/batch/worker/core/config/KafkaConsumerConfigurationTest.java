package io.github.pinpols.batch.worker.core.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaConsumerConfigurationTest {

  @Test
  void batchListenerFactoryRejectsTotalConcurrentPollCapacityLargerThanWorkerCapacityWhenEnabled() {
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
  void batchListenerFactoryAllowsSamePollBatchAsWorkerConcurrencyWhenEnabled() {
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
  void batchListenerFactoryAllowsInvalidBatchSizingWhenBatchClaimDisabled() {
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
