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
}
