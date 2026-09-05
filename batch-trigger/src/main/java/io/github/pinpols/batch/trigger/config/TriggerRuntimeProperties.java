package io.github.pinpols.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.trigger.runtime")
/** Trigger 轮询、补偿与运行时保护参数。 */
public class TriggerRuntimeProperties {

  private long misfireCatchUpThresholdSeconds = 60L;

  private long readinessWindowSeconds = 7200L;

  private long readinessRecheckIntervalSeconds = 30L;

  /** 手工 launch 入口允许同时进入数据库事务的请求数；超过后立即返回 429。 */
  private int apiLaunchMaxConcurrency = 64;

  /** 是否根据本地请求耗时动态收缩/恢复手工 launch 的并发预算。默认关闭，保持既有固定闸门语义。 */
  private boolean apiLaunchAdaptiveEnabled = false;

  /** 自适应闸门的最低并发预算，防止一次慢请求把入口完全关死。 */
  private int apiLaunchMinConcurrency = 16;

  /** 请求耗时达到该阈值时，认为本实例下游已出现背压并收缩预算。 */
  private long apiLaunchSlowRequestThresholdMillis = 1000L;
}
