package io.github.pinpols.batch.common.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一的 Kafka producer 调优配置(绑定 {@code spring.kafka.producer.*})。
 *
 * <p>背景:本系统用裸 {@code spring-kafka},未引入 Spring Boot 的 Kafka AutoConfiguration(classpath 无 {@code
 * KafkaProperties}),因此 {@code spring.kafka.producer.*} 这段 yml 之前没有任何组件读取—— orchestrator /
 * worker-core / trigger 各自 {@code new DefaultKafkaProducerFactory(手搓 Map)},导致 acks / 幂等 / 超时 /
 * 背压参数三处漂移(orchestrator 甚至全缺)。本类把这段 yml 真正绑定起来,配合 {@link BatchKafkaProducerSupport}
 * 让三处共用一份配置,单一真相源。
 *
 * <p>{@code properties} 原样透传 Kafka client 配置(键即 {@code enable.idempotence} / {@code
 * delivery.timeout.ms} / {@code request.timeout.ms} / {@code buffer.memory} / {@code max.block.ms}
 * / {@code max.in.flight.requests.per.connection} 等)。
 */
@Data
@ConfigurationProperties(prefix = "spring.kafka.producer")
public class BatchKafkaProducerProperties {

  /** 应答级别;默认 all(配合 replication-factor=3 + min.insync.replicas=2 防丢)。 */
  private String acks = "all";

  /** broker 抖动 / leader 切换时的 in-process 重试次数;配合幂等避免重复。 */
  private int retries = 5;

  /** 透传到 Kafka client 的额外 producer 配置(键即原生配置名)。 */
  private Map<String, String> properties = new LinkedHashMap<>();
}
