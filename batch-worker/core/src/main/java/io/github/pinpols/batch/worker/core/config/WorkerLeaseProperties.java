package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker lease renewer / 任务执行客户端共享配置。
 *
 * <p>之前散落:
 *
 * <ul>
 *   <li>{@code
 *       WorkerTaskLeaseRenewer}:consecutive-failure-alert-threshold、circuit-half-open-tick-interval
 *   <li>{@code HttpTaskExecutionClient}:renew-batch-max-items
 * </ul>
 *
 * <p>都在 {@code batch.worker.lease.*} 命名空间下,集中。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.lease")
public class WorkerLeaseProperties {

  /** 连续 renew 失败到该阈值时发告警(默认 3 次)。 */
  private int consecutiveFailureAlertThreshold = 3;

  /** 熔断 OPEN 后每 N 个 tick 强制半开探测一次。默认 5 = ~50s @ renew 周期 10s。 */
  private int circuitHalfOpenTickInterval = 5;

  /** 单次 renewBatch 请求最多带几条 lease(超出会拆批)。默认 256。 */
  private int renewBatchMaxItems = 256;
}
