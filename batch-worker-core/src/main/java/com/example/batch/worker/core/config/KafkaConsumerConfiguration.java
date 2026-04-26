package com.example.batch.worker.core.config;

import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfiguration {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${spring.kafka.consumer.auto-offset-reset:latest}")
  private String autoOffsetReset;

  @Value("${spring.kafka.consumer.max-poll-records:20}")
  private int maxPollRecords;

  @Value("${spring.kafka.consumer.fetch-min-size:1024}")
  private int fetchMinBytes;

  @Value("${spring.kafka.consumer.fetch-max-wait:500}")
  private int fetchMaxWaitMs;

  // 背压场景下 Semaphore 可能阻塞线程较长时间；此值须大于单批最长处理时延
  @Value("${spring.kafka.consumer.max-poll-interval-ms:600000}")
  private int maxPollIntervalMs;

  // PATTERN/REGEX topic 订阅必须等到 metadata 刷新才能发现新创建的 topic。Kafka 客户端默认 5min，
  // 对生产 OK（topic 多预创建）但 e2e/集成测试 timeout 通常 ≤ 2min 接收不到。降到 30s 给一个
  // 既保护 broker 又能让测试感知新 topic 的折衷值。
  @Value("${spring.kafka.consumer.metadata-max-age-ms:30000}")
  private int metadataMaxAgeMs;

  @Bean
  public ProducerFactory<String, String> kafkaProducerFactory() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    return new DefaultKafkaProducerFactory<>(properties);
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> kafkaProducerFactory,
      ObservationRegistry observationRegistry) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(kafkaProducerFactory);
    template.setObservationEnabled(true);
    template.setObservationRegistry(observationRegistry);
    return template;
  }

  @Bean
  public ConsumerFactory<String, String> kafkaConsumerFactory() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
    properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
    properties.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
    properties.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);
    properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
    properties.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, metadataMaxAgeMs);
    return new DefaultKafkaConsumerFactory<>(properties);
  }

  @Bean(name = "kafkaListenerContainerFactory")
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> kafkaConsumerFactory,
      ObservationRegistry observationRegistry) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(kafkaConsumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setObservationRegistry(observationRegistry);
    return factory;
  }
}
