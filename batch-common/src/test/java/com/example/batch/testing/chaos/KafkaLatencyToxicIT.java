package com.example.batch.testing.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 故障注入意图:验证 Kafka 路径在 broker 高延迟(500ms+)下,带短 delivery-timeout 的 producer 会 抛 TimeoutException →
 * 上游(如 OutboxPublishCircuitBreaker)可据此累积失败次数并开闸熔断。
 *
 * <p>对应业务路径:Outbox forwarder → KafkaTemplate.send → 超时 → 标 FAILED → CB 转 OPEN。
 *
 * <p>本 IT 在 batch-common 层只验"infra 端确实超时",熔断器侧的状态机判定在 orchestrator 的
 * OutboxPublishCircuitBreakerKafkaFailureIntegrationTest 已覆盖,此处不重复。
 */
@DisplayName("Kafka 500ms 延迟注入 — 短 delivery-timeout producer 触发 TimeoutException(熔断器输入信号)")
class KafkaLatencyToxicIT extends AbstractChaosIntegrationTest {

  @Test
  @DisplayName("注入 500ms 下游延迟 → producer 在 200ms delivery-timeout 内必抛 TimeoutException")
  void shouldTimeoutWhenKafkaLatencyExceedsDeliveryDeadline() throws Exception {
    Properties baseConfig = producerConfig();

    // 故障消除时(无 toxic)同样配置必须能成功发出 — 防止"用例本身不可达"误报
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(baseConfig)) {
      RecordMetadata md =
          producer
              .send(new ProducerRecord<>("chaos-kafka-it", "k", "warmup"))
              .get(10, TimeUnit.SECONDS);
      assertThat(md).isNotNull();
    }

    // 注入 3s 单向延迟 — 远超 producer 配置的 2s delivery-timeout,send 必抛 TimeoutException
    withLatency(
        ProxyTarget.KAFKA,
        Duration.ofMillis(3000),
        () -> {
          try (KafkaProducer<String, String> producer = new KafkaProducer<>(baseConfig)) {
            assertThatThrownBy(
                    () ->
                        producer
                            .send(new ProducerRecord<>("chaos-kafka-it", "k", "latency-payload"))
                            .get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
          }
        });
  }

  @Test
  @DisplayName("故障消除后立即再发 → producer 恢复正常(验自愈路径,熔断 half-open 探测的 infra 前置条件)")
  void shouldRecoverImmediatelyAfterLatencyRemoved() throws Exception {
    // 注入延迟,确认失败(3s 单向 > 2s delivery-timeout)
    withLatency(
        ProxyTarget.KAFKA,
        Duration.ofMillis(3000),
        () -> {
          try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig())) {
            assertThatThrownBy(
                    () ->
                        producer
                            .send(new ProducerRecord<>("chaos-kafka-it", "k", "fail"))
                            .get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
          }
        });

    // withLatency 退出后 toxic 已移除 — 立即重试必须成功
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig())) {
      RecordMetadata md =
          producer
              .send(new ProducerRecord<>("chaos-kafka-it", "k", "recovered"))
              .get(10, TimeUnit.SECONDS);
      assertThat(md.hasOffset()).isTrue();
    }
  }

  private Properties producerConfig() {
    Properties p = new Properties();
    p.putAll(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProxiedBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.ACKS_CONFIG, "1",
            ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000",
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000",
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "2000",
            ProducerConfig.RETRIES_CONFIG, "0"));
    return p;
  }
}
