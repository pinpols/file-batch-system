package com.example.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trigger outbox relay 调度参数。
 *
 * <p>{@link com.example.batch.trigger.application.TriggerOutboxRelay} 之前散落的 4 个 {@code @Value}
 * 收敛到这里。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.outbox")
public class TriggerOutboxRelayProperties {

  /** Outbox 轮询间隔(ms)。默认 200。 */
  private long pollIntervalMillis = 200L;

  /** 单批最多扫多少条 outbox 记录。默认 100。 */
  private int batchSize = 100;

  /** PUBLISHING 状态超时秒数,超时后回收为 NEW。默认 120。 */
  private long publishingTimeoutSeconds = 120L;

  /** 单条最大发布尝试次数(达到后标 GIVE_UP)。默认 10。 */
  private int maxPublishAttempts = 10;

  /** Relay scheduler 关闭等待秒数(优雅 shutdown,等当前 poll 跑完)。默认 15。 */
  private int shutdownAwaitSeconds = 15;
}
