package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Orchestrator trigger.launch.v1 consumer 配置。
 *
 * <p>{@link OrchestratorKafkaConsumerConfiguration} 之前散落的 7 个 {@code @Value} 收敛到这里。
 * bootstrap-servers 仍走 Spring 自带 {@code spring.kafka.bootstrap-servers}。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.consumer")
public class TriggerConsumerProperties {

  /** Consumer group id。默认 {@code orchestrator-trigger-launch}。 */
  private String groupId = "orchestrator-trigger-launch";

  /** offset 兜底策略;首次起服 / 重置时使用。默认 earliest 避免丢消息。 */
  private String autoOffsetReset = "earliest";

  /** 单次 poll 最大记录数。默认 50。 */
  private int maxPollRecords = 50;

  /** poll 间最大间隔(ms),超过被踢出 group。默认 300 000(5 分钟)。 */
  private int maxPollIntervalMs = 300_000;

  /** ErrorHandler 配置子分组。 */
  private final ErrorHandler errorHandler = new ErrorHandler();

  @Data
  public static class ErrorHandler {
    /** 瞬时错误重试次数(命中 not-retryable 异常会一次跳过 offset)。默认 3。 */
    private int retryAttempts = 3;

    /** FixedBackOff 重试间隔(ms)。默认 2000。 */
    private long retryBackoffMs = 2_000L;
  }
}
