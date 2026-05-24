package com.example.batch.trigger.config;

import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * ADR-010 Stage 4: trigger 端 Kafka 生产者配置（固化无开关）。
 *
 * <p>设计:与 batch-orchestrator / batch-worker-core 保持参数一致(acks=all, idempotence-via-retry), 但 trigger
 * 流量小,无需 batching / linger 优化。
 *
 * <p>acks/retries/send-timeout 经 {@link TriggerKafkaProperties} 集中管理;bootstrap-servers 仍直读 Spring
 * 自带 {@code spring.kafka.bootstrap-servers} 不入 Properties(避免与 Spring KafkaProperties 重复)。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TriggerKafkaProperties.class)
@RequiredArgsConstructor
public class TriggerKafkaProducerConfiguration {

  private final TriggerKafkaProperties kafkaProperties;

  @Bean
  public ProducerFactory<String, String> triggerKafkaProducerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.ACKS_CONFIG, kafkaProperties.getProducer().getAcks());
    properties.put(ProducerConfig.RETRIES_CONFIG, kafkaProperties.getProducer().getRetries());
    // 幂等生产者 + 顺序保证;send 失败由 outbox relay 退避兜底,无需 Producer 端 max.in.flight 限制。
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    // max.block.ms:buffer 满 / metadata fetch 阻塞上限。默认 60s 远超 sendTimeoutSeconds(默认 10s),
    // broker 不可达时会让 relay 线程在 send() 内阻塞 2x sendTimeout。取 sendTimeout 的 80%(最少 1s)
    // 作内部阻塞上限,超出由外层 sendTimeout 兜底取消 future,避免双层超时叠加。
    long sendTimeoutMillis = Math.max(1_000L, kafkaProperties.getSendTimeoutSeconds() * 1_000L);
    long maxBlockMillis = Math.max(1_000L, (long) (sendTimeoutMillis * 0.8));
    properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMillis);
    return new DefaultKafkaProducerFactory<>(properties);
  }

  @Bean
  public KafkaTemplate<String, String> triggerKafkaTemplate(
      ProducerFactory<String, String> triggerKafkaProducerFactory,
      ObservationRegistry observationRegistry) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(triggerKafkaProducerFactory);
    template.setObservationEnabled(true);
    template.setObservationRegistry(observationRegistry);
    return template;
  }
}
