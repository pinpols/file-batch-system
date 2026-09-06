package io.github.pinpols.batch.trigger.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "batch.trigger.runtime")
/** Trigger 轮询、补偿与运行时保护参数。 */
public class TriggerRuntimeProperties {

  private long misfireCatchUpThresholdSeconds = 60L;

  private long readinessWindowSeconds = 7200L;

  private long readinessRecheckIntervalSeconds = 30L;

  /** 手工 launch 入口允许同时进入数据库事务的请求数；超过后立即返回 429。 */
  @Min(1)
  private int apiLaunchMaxConcurrency = 8;

  /** 是否根据本地请求耗时动态收缩/恢复手工 launch 的并发预算。默认关闭，保持固定闸门语义。 */
  private boolean apiLaunchAdaptiveEnabled = false;

  /** 自适应闸门的最低并发预算，防止一次慢请求把入口完全关死。 */
  @Min(1)
  private int apiLaunchMinConcurrency = 2;

  /** 请求耗时达到该阈值时，认为本实例下游已出现背压并收缩预算。 */
  @Min(1)
  private long apiLaunchSlowRequestThresholdMillis = 1_000L;

  /** API 执行预算耗尽时可短暂等待的请求数；超过后立即返回 429，避免无限堆积 Servlet 线程。 */
  @Min(0)
  @Max(128)
  private int apiLaunchQueueCapacity = 8;

  /** API 请求在本地 admission 队列中最多等待多久；到期仍无执行预算则返回 429。 */
  @Min(0)
  private long apiLaunchQueueWaitMillis = 1_000L;

  /** 为 outbox relay、健康检查和管理请求保留的平台库连接数。 */
  @Min(0)
  private int apiLaunchDbReserveConnections = 2;
}
