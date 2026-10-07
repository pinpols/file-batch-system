package io.github.pinpols.batch.worker.core.config;

import io.github.pinpols.batch.common.config.BatchKafkaProducerProperties;
import io.github.pinpols.batch.common.config.BatchKafkaProducerSupport;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@RequiredArgsConstructor
@EnableConfigurationProperties({
  BatchKafkaProducerProperties.class,
  WorkerBatchClaimProperties.class,
  WorkerKafkaProperties.class
})
public class KafkaConsumerConfiguration {

  private final WorkerKafkaProperties kafkaProperties;

  // worker → orchestrator REPORT 路径 producer:统一走全局 spring.kafka.producer.* 调优
  // (acks=all / 幂等 / max.in.flight / delivery & request 超时 / buffer.memory / max.block 背压)。
  // 之前 hand-build 缺 buffer.memory & max.block.ms,broker 抖动时 send() 可能在 buffer 满后久阻塞;
  // 收敛到 BatchKafkaProducerSupport 后与 orchestrator / trigger 同源,消除三处漂移。
  @Bean
  public ProducerFactory<String, String> kafkaProducerFactory(
      BatchKafkaProducerProperties kafkaProducerProperties) {
    return new DefaultKafkaProducerFactory<>(BatchKafkaProducerSupport.stringProducerConfig(
        kafkaProperties.getBootstrapServers(), kafkaProducerProperties));
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
    WorkerKafkaProperties.Consumer consumer = kafkaProperties.getConsumer();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumer.getAutoOffsetReset());
    properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, consumer.getMaxPollRecords());
    properties.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, consumer.getFetchMinSize());
    properties.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, consumer.getFetchMaxWait());
    properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, consumer.getMaxPollIntervalMs());
    properties.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, consumer.getMetadataMaxAgeMs());
    return new DefaultKafkaConsumerFactory<>(properties);
  }

  @Bean(name = "kafkaListenerContainerFactory")
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> kafkaConsumerFactory,
      ObservationRegistry observationRegistry) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(kafkaConsumerFactory);
    factory.setConcurrency(Math.max(1, kafkaProperties.getListener().getConcurrency()));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setObservationRegistry(observationRegistry);
    return factory;
  }

  /**
   * ADR-046 P2 切片 2.3c:批量 listener container factory(setBatchListener=true)。 子类的批量
   * {@code @KafkaListener}(仅 {@code batch.worker.batch-claim.enabled=true} 时 autoStartup)用它, 一次
   * poll 收 List&lt;String&gt; payload 交 {@code doConsumeBatch}。同 MANUAL_IMMEDIATE ack(整批一次确认)。
   */
  @Bean(name = "batchKafkaListenerContainerFactory")
  public ConcurrentKafkaListenerContainerFactory<String, String> batchKafkaListenerContainerFactory(
      ConsumerFactory<String, String> kafkaConsumerFactory,
      ObservationRegistry observationRegistry,
      WorkerBatchClaimProperties batchClaimProperties,
      WorkerConcurrencyProperties concurrencyProperties) {
    validateBatchBackpressureConfiguration(batchClaimProperties, concurrencyProperties);
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(kafkaConsumerFactory);
    factory.setConcurrency(Math.max(1, kafkaProperties.getListener().getConcurrency()));
    factory.setBatchListener(true);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setObservationRegistry(observationRegistry);
    return factory;
  }

  private void validateBatchBackpressureConfiguration(
      WorkerBatchClaimProperties batchClaimProperties,
      WorkerConcurrencyProperties concurrencyProperties) {
    if (!batchClaimProperties.isEnabled()) {
      return;
    }
    int maxConcurrentTasks = concurrencyProperties.getMaxConcurrentTasks();
    int listenerConcurrency = kafkaProperties.getListener().getConcurrency();
    int maxPollRecords = kafkaProperties.getConsumer().getMaxPollRecords();
    int effectiveListenerConcurrency = Math.max(1, listenerConcurrency);
    long requiredPermits = (long) Math.max(1, maxPollRecords) * effectiveListenerConcurrency;
    if (requiredPermits <= maxConcurrentTasks) {
      return;
    }
    throw new IllegalStateException("batch.worker.batch-claim.enabled=true requires "
        + WorkerRuntimeConfiguration.MAX_CONCURRENT_TASKS_PROPERTY
        + " "
        + ">= spring.kafka.listener.concurrency * spring.kafka.consumer.max-poll-records; got "
        + "listener-concurrency="
        + effectiveListenerConcurrency
        + ", max-poll-records="
        + maxPollRecords
        + ", max-concurrent-tasks="
        + maxConcurrentTasks
        + ", required-permits="
        + requiredPermits);
  }
}
