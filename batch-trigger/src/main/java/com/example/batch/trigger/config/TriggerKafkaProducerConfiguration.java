package com.example.batch.trigger.config;

import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * ADR-010 Stage 4: trigger 端 Kafka 生产者配置(仅当 async-launch.enabled=true 时启用)。
 *
 * <p>设计:与 batch-orchestrator / batch-worker-core 保持参数一致(acks=all, idempotence-via-retry), 但 trigger
 * 流量小,无需 batching / linger 优化。
 *
 * <p>启用条件:配置 {@code batch.trigger.async-launch.enabled=true}。否则不创建 ProducerFactory / KafkaTemplate
 * bean,trigger 不需要 Kafka 资产。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "batch.trigger.async-launch",
    name = "enabled",
    havingValue = "true")
public class TriggerKafkaProducerConfiguration {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${batch.trigger.kafka.producer.acks:all}")
  private String acks;

  @Value("${batch.trigger.kafka.producer.retries:5}")
  private int retries;

  @Bean
  public ProducerFactory<String, String> triggerKafkaProducerFactory() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.ACKS_CONFIG, acks);
    properties.put(ProducerConfig.RETRIES_CONFIG, retries);
    // 幂等生产者 + 顺序保证;send 失败由 outbox relay 退避兜底,无需 Producer 端 max.in.flight 限制。
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
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
