package com.example.batch.trigger.config;

import com.example.batch.common.config.BatchKafkaProducerProperties;
import com.example.batch.common.config.BatchKafkaProducerSupport;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerConfig;
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
@EnableConfigurationProperties({TriggerKafkaProperties.class, BatchKafkaProducerProperties.class})
@RequiredArgsConstructor
public class TriggerKafkaProducerConfiguration {

  private final TriggerKafkaProperties kafkaProperties;
  private final BatchKafkaProducerProperties commonProducerProperties;

  @Bean
  public ProducerFactory<String, String> triggerKafkaProducerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    // base 统一走全局 spring.kafka.producer.*(acks/retries/幂等/delivery & request 超时/buffer.memory),
    // 与 orchestrator / worker-core 同源,消除三处漂移。
    Map<String, Object> properties =
        BatchKafkaProducerSupport.stringProducerConfig(bootstrapServers, commonProducerProperties);
    // trigger 专属覆盖:max.block.ms 不取全局(默认 5s),而按本模块 sendTimeoutSeconds 推导更紧的
    // 内部阻塞上限——取 sendTimeout 的 80%(最少 1s),超出由 KafkaTriggerEventPublisher 外层
    // sendTimeout 回退取消 future,避免双层超时叠加。
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
