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
  void batchListenerFactoryRejectsPollBatchLargerThanWorkerConcurrencyWhenEnabled() {
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
    ReflectionTestUtils.setField(configuration, "batchClaimEnabled", true);
    ReflectionTestUtils.setField(configuration, "maxPollRecords", 10);
    ReflectionTestUtils.setField(configuration, "maxConcurrentTasks", 8);

    assertThatThrownBy(
            () ->
                configuration.batchKafkaListenerContainerFactory(
                    consumerFactory(), ObservationRegistry.NOOP))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-poll-records=10")
        .hasMessageContaining("max-concurrent-tasks=8");
  }

  @Test
  void batchListenerFactoryAllowsSamePollBatchAsWorkerConcurrencyWhenEnabled() {
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
    ReflectionTestUtils.setField(configuration, "batchClaimEnabled", true);
    ReflectionTestUtils.setField(configuration, "maxPollRecords", 8);
    ReflectionTestUtils.setField(configuration, "maxConcurrentTasks", 8);
    ReflectionTestUtils.setField(configuration, "listenerConcurrency", 1);

    assertThatCode(
            () ->
                configuration.batchKafkaListenerContainerFactory(
                    consumerFactory(), ObservationRegistry.NOOP))
        .doesNotThrowAnyException();
  }

  @Test
  void batchListenerFactoryAllowsInvalidBatchSizingWhenBatchClaimDisabled() {
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
    ReflectionTestUtils.setField(configuration, "batchClaimEnabled", false);
    ReflectionTestUtils.setField(configuration, "maxPollRecords", 10);
    ReflectionTestUtils.setField(configuration, "maxConcurrentTasks", 8);
    ReflectionTestUtils.setField(configuration, "listenerConcurrency", 1);

    assertThatCode(
            () ->
                configuration.batchKafkaListenerContainerFactory(
                    consumerFactory(), ObservationRegistry.NOOP))
        .doesNotThrowAnyException();
  }

  @SuppressWarnings("unchecked")
  private ConsumerFactory<String, String> consumerFactory() {
    return mock(ConsumerFactory.class);
  }
}
