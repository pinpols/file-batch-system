package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重试治理配置（{@code batch.retry}）。
 *
 * <p>控制 {@code retry_schedule} 表的扫描节奏 + 默认重试策略基线。job_definition 可单独 覆盖 {@code retry_policy}（NONE /
 * FIXED / EXPONENTIAL）和 {@code max_retry_count}， 这里的值仅作为未配置时的兜底。
 */
@Data
@ConfigurationProperties(prefix = "batch.retry")
public class RetryGovernanceProperties {

  /** 单次扫描批次大小。批次越大单次扫描压力越大，但调度更平滑；建议 50-500。 */
  private int batchSize = 100;

  /** retry_schedule 扫描间隔（ms）。间隔短 → 重试更敏感但 DB 压力大。 */
  private long pollIntervalMillis = 10000L;

  /** {@code FIXED} 策略的固定退避（秒）。 */
  private long fixedDelaySeconds = 60L;

  /** {@code EXPONENTIAL} 策略的倍数。next = prev * multiplier。 */
  private long exponentialMultiplier = 2L;

  /** 重试最大单次延迟（秒）。{@code EXPONENTIAL} 上限，防退避无限增长。 */
  private long maxDelaySeconds = 3600L;

  /** 默认最大重试次数（job_definition 未配 max_retry_count 时使用）。 */
  private int defaultMaxRetryCount = 3;
}
