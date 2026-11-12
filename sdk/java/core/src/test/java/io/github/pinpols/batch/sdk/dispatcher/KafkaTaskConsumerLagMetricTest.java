package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Kafka 消费滞后指标 — 每轮 poll 后读取 records-lag-max 并保守更新")
class KafkaTaskConsumerLagMetricTest {

  private final BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
      .baseUrl("http://localhost:0")
      .tenantId("tx")
      .workerCode("w-1")
      .kafkaBootstrap("kafka:9092")
      .kafkaTopicPattern("batch.task.dispatch.tx.*")
      .kafkaGroupId("g")
      .maxConcurrentTasks(2)
      .build();

  private TaskDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
  }

  private static Metric metricOf(Object value) {
    Metric metric = mock(Metric.class);
    when(metric.metricValue()).thenReturn(value);
    return metric;
  }

  private static MetricName name(String metricName) {
    return new MetricName(metricName, "consumer-fetch-manager-metrics", "", Map.of());
  }

  /** 每次 poll 返回空批次, 第 {@code polls} 次之后抛出唤醒异常使循环正常结束。 */
  @SuppressWarnings("unchecked")
  private long runPolls(Consumer<String, byte[]> consumer, int polls) {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    ConsumerRecords<String, byte[]> empty = ConsumerRecords.empty();
    var stub = when(consumer.poll(any()));
    for (int i = 0; i < polls; i++) {
      stub = stub.thenReturn(empty);
    }
    stub.thenThrow(new WakeupException());
    KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper());
    kafka.run();
    return kafka.consumerLagMax();
  }

  @SuppressWarnings("unchecked")
  private Consumer<String, byte[]> mockConsumer() {
    return mock(Consumer.class);
  }

  @Test
  @DisplayName("跳过其它指标, 命中 records-lag-max 后取整数部分并停止遍历")
  void shouldReadLagMax_whenMetricPresent() {
    Consumer<String, byte[]> consumer = mockConsumer();
    Map<MetricName, Metric> metrics = new LinkedHashMap<>();
    metrics.put(name("fetch-rate"), metricOf(99.0d));
    metrics.put(name("records-lag-max"), metricOf(7.9d));
    doReturnMetrics(consumer, metrics);

    assertThat(runPolls(consumer, 1)).isEqualTo(7L);
  }

  @Test
  @DisplayName("指标缺失、非数值、NaN 或负数时保持初始未知值")
  void shouldKeepUnknown_whenMetricMissingOrInvalid() {
    for (Object invalid : new Object[] {Double.NaN, -1.0d, "n/a"}) {
      Consumer<String, byte[]> consumer = mockConsumer();
      doReturnMetrics(consumer, Map.of(name("records-lag-max"), metricOf(invalid)));
      assertThat(runPolls(consumer, 1)).as("value=%s", invalid).isEqualTo(-1L);
    }
    Consumer<String, byte[]> noMetric = mockConsumer();
    doReturnMetrics(noMetric, Map.of());
    assertThat(runPolls(noMetric, 1)).isEqualTo(-1L);
  }

  @Test
  @DisplayName("读取指标抛运行时异常时吞掉并保持上一次值, 消费循环不崩溃")
  void shouldSwallowFailure_whenMetricsThrow() {
    Consumer<String, byte[]> consumer = mockConsumer();
    when(consumer.metrics()).thenThrow(new IllegalStateException("closed"));

    assertThat(runPolls(consumer, 1)).isEqualTo(-1L);
  }

  private static void doReturnMetrics(
      Consumer<String, byte[]> consumer, Map<MetricName, Metric> metrics) {
    when(consumer.metrics()).thenAnswer(inv -> metrics);
  }
}
