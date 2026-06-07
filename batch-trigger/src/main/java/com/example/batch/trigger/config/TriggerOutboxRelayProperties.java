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

  /**
   * Relay scheduler 是否在关闭时等待当前 poll 完成。
   *
   * <p>默认 false：trigger outbox 依靠 CAS、重试与 stale PUBLISHING 回收保证恢复；关闭期继续等待同步 Kafka send 可能拖住 JVM，并让
   * Redis/DB 进入 STOPPING 后被 poll 线程再次访问。
   */
  private boolean waitForTasksToCompleteOnShutdown = false;

  /** Relay scheduler 关闭等待秒数。默认 5；通常只用于等待线程响应 interrupt。 */
  private int shutdownAwaitSeconds = 5;

  /**
   * Relay scheduler 的 SmartLifecycle phase。
   *
   * <p>Spring 停机时高 phase 先停。Redis LettuceConnectionFactory 默认 phase=0；这里显式高 phase， 保证 relay
   * 调度线程先取消并 drain，再销毁 Redis 连接。
   */
  private int schedulerPhase = 1_073_741_823;
}
