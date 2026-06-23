package io.github.pinpols.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trigger Kafka producer 客户端参数。
 *
 * <p>之前散落:
 *
 * <ul>
 *   <li>{@link TriggerKafkaProducerConfiguration} 上 {@code
 *       batch.trigger.kafka.producer.{acks,retries}}
 *   <li>{@code KafkaTriggerEventPublisher} 上 {@code batch.trigger.kafka.send-timeout-seconds}
 * </ul>
 *
 * <p>统一收敛到本类。{@code spring.kafka.bootstrap-servers} 走 Spring 自己的 KafkaProperties,不在此重复。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.kafka")
public class TriggerKafkaProperties {

  /** 单次同步 send 等待 ack 的超时(秒),超时记 PublishResult.fail。默认 10。 */
  private int sendTimeoutSeconds = 10;

  /** ProducerConfig 相关参数子分组。 */
  private final Producer producer = new Producer();

  @Data
  public static class Producer {
    /** ProducerConfig.ACKS_CONFIG,默认 "all" 强一致。 */
    private String acks = "all";

    /** ProducerConfig.RETRIES_CONFIG。默认 5(idempotence via retry,与 batch-orchestrator/worker 对齐)。 */
    private int retries = 5;
  }
}
