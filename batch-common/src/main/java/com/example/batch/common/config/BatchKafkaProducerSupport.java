package com.example.batch.common.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一构建 String⇄String Kafka producer 配置,消除 orchestrator / worker-core / trigger 各自 hand-build 的 acks
 * / 幂等 / 超时 / 背压参数漂移。
 *
 * <p>来源:{@link BatchKafkaProducerProperties}(绑定 {@code spring.kafka.producer.*})+ 调用方传入的 {@code
 * bootstrap-servers}(仍走 {@code spring.kafka.bootstrap-servers},不入 properties 避免重复)。 返回可变
 * Map,调用方可在其上再 {@code put} 覆盖个别键(如 trigger 按 sendTimeout 推导的 {@code max.block.ms})。
 *
 * <p><b>故意不依赖 kafka-clients 类型</b>:batch-common 主 classpath 不引 kafka-clients(仅 test scope),故此处用
 * Kafka 原生配置键字符串 + serializer 全限定类名字符串(Kafka client 会自行解析 类名),避免给 batch-common 增加运行时 kafka 依赖。
 */
public final class BatchKafkaProducerSupport {

  private static final String BOOTSTRAP_SERVERS = "bootstrap.servers";
  private static final String KEY_SERIALIZER = "key.serializer";
  private static final String VALUE_SERIALIZER = "value.serializer";
  private static final String ACKS = "acks";
  private static final String RETRIES = "retries";
  private static final String STRING_SERIALIZER =
      "org.apache.kafka.common.serialization.StringSerializer";

  private BatchKafkaProducerSupport() {}

  /**
   * 构建统一的 producer 配置 Map(bootstrap + String serializers + 全局 acks/retries/properties.*)。
   *
   * @param bootstrapServers {@code spring.kafka.bootstrap-servers}
   * @param props 绑定 {@code spring.kafka.producer.*} 的统一配置
   * @return 可变配置 Map(可被调用方覆盖个别键)
   */
  public static Map<String, Object> stringProducerConfig(
      String bootstrapServers, BatchKafkaProducerProperties props) {
    Map<String, Object> cfg = new HashMap<>();
    cfg.put(BOOTSTRAP_SERVERS, bootstrapServers);
    cfg.put(KEY_SERIALIZER, STRING_SERIALIZER);
    cfg.put(VALUE_SERIALIZER, STRING_SERIALIZER);
    if (props.getAcks() != null && !props.getAcks().isBlank()) {
      cfg.put(ACKS, props.getAcks());
    }
    cfg.put(RETRIES, props.getRetries());
    // properties.* 原样透传:键即 Kafka client 配置名,值为 String(client 自行解析)
    if (props.getProperties() != null) {
      cfg.putAll(props.getProperties());
    }
    return cfg;
  }
}
