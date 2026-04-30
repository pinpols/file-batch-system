package com.example.batch.orchestrator.config;

import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * ADR-010 Stage 4: orchestrator Kafka 消费侧配置(仅当 trigger async-launch 路径启用)。
 *
 * <p>启用条件:{@code batch.trigger.async-launch.enabled=true}。两边开关一致避免单边激活——trigger 不发但 orchestrator 起
 * listener 浪费连接;trigger 发了但 orchestrator 不接更危险(消息堆积)。
 *
 * <p>消费模式:MANUAL_IMMEDIATE ack(consumer 端处理完才 ack);失败抛异常 → Spring Kafka 默认 SeekTo 行为重试,失败次数耗尽走
 * DLQ(本 Stage 暂不配 DLQ topic,依赖 orchestrator 端 uk_job_instance_tenant_dedup 兜底重复消息)。
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(
    prefix = "batch.trigger.async-launch",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrchestratorKafkaConsumerConfiguration {

  /** Listener factory bean 名称 — 给 @KafkaListener(containerFactory=...) 引用。 */
  public static final String TRIGGER_LISTENER_FACTORY =
      "triggerLaunchKafkaListenerContainerFactory";

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${batch.trigger.consumer.group-id:orchestrator-trigger-launch}")
  private String groupId;

  @Value("${batch.trigger.consumer.auto-offset-reset:earliest}")
  private String autoOffsetReset;

  @Value("${batch.trigger.consumer.max-poll-records:50}")
  private int maxPollRecords;

  @Value("${batch.trigger.consumer.max-poll-interval-ms:300000}")
  private int maxPollIntervalMs;

  @Bean
  public ConsumerFactory<String, String> triggerLaunchConsumerFactory() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    // earliest 兜底:首次起服 / 重置时不丢消息;正常运行靠 commit offset
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
    // 关 auto-commit,走 MANUAL_IMMEDIATE
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
    properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
    return new DefaultKafkaConsumerFactory<>(properties);
  }

  @Bean(name = TRIGGER_LISTENER_FACTORY)
  public ConcurrentKafkaListenerContainerFactory<String, String>
      triggerLaunchKafkaListenerContainerFactory(
          ConsumerFactory<String, String> triggerLaunchConsumerFactory,
          ObservationRegistry observationRegistry) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(triggerLaunchConsumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setObservationRegistry(observationRegistry);
    return factory;
  }
}
