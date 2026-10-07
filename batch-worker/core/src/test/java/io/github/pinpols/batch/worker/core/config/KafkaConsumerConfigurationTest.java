package io.github.pinpols.batch.worker.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@DisplayName("Kafka 消费者容器工厂: 批量领取开关下的并发容量校验")
class KafkaConsumerConfigurationTest {

  @Test
  @DisplayName("批量领取开启时, 监听并发与单次拉取条数相乘超过任务并发许可则拒绝创建容器并提示实际所需许可")
  void shouldRejectFactory_whenPollCapacityExceedsTaskPermits() {
    KafkaConsumerConfiguration configuration = configuration(4, 8);

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
    KafkaConsumerConfiguration configuration = configuration(4, 8);

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
    KafkaConsumerConfiguration configuration = configuration(1, 10);

    assertThatCode(() -> configuration.batchKafkaListenerContainerFactory(
            consumerFactory(),
            ObservationRegistry.NOOP,
            batchClaimProperties(false),
            concurrencyProperties(8)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("类型化配置完整传入 Kafka 客户端, 单条与批量容器保持手动确认及观测设置")
  void shouldPreserveClientAndContainerSettings() {
    WorkerKafkaProperties properties = new WorkerKafkaProperties();
    properties.setBootstrapServers("broker-a:9092,broker-b:9092");
    properties.getConsumer().setAutoOffsetReset("earliest");
    properties.getConsumer().setFetchMinSize(2048);
    properties.getConsumer().setFetchMaxWait(750);
    properties.getConsumer().setMaxPollIntervalMs(1_200_000);
    properties.getConsumer().setMetadataMaxAgeMs(15_000);
    properties.getConsumer().setMaxPollRecords(2);
    properties.getListener().setConcurrency(3);
    KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration(properties);
    ConsumerFactory<String, String> consumer = configuration.kafkaConsumerFactory();
    assertThat(consumer.getConfigurationProperties())
        .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker-a:9092,broker-b:9092")
        .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 2048)
        .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 750)
        .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1_200_000)
        .containsEntry(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 15_000)
        .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2);
    var single = configuration.kafkaListenerContainerFactory(consumer, ObservationRegistry.NOOP);
    var batch = configuration.batchKafkaListenerContainerFactory(
        consumer, ObservationRegistry.NOOP, batchClaimProperties(true), concurrencyProperties(6));
    assertThat(single.createContainer("test-topic").getConcurrency()).isEqualTo(3);
    assertThat(batch.createContainer("test-topic").getConcurrency()).isEqualTo(3);
    assertThat(single.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    assertThat(batch.getContainerProperties().getAckMode())
        .isEqualTo(single.getContainerProperties().getAckMode());
    assertThat(single.getContainerProperties().isObservationEnabled()).isTrue();
    assertThat(batch.getContainerProperties().isObservationEnabled()).isTrue();
    assertThat(batch.isBatchListener()).isTrue();
  }

  private static KafkaConsumerConfiguration configuration(int concurrency, int maxPollRecords) {
    WorkerKafkaProperties properties = new WorkerKafkaProperties();
    properties.getListener().setConcurrency(concurrency);
    properties.getConsumer().setMaxPollRecords(maxPollRecords);
    return new KafkaConsumerConfiguration(properties);
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
