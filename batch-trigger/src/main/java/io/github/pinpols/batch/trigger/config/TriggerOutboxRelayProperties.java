package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Trigger outbox relay 调度参数。
 *
 * <p>{@link io.github.pinpols.batch.trigger.application.TriggerOutboxRelay} 之前散落的 4 个
 * {@code @Value} 收敛到这里。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.trigger.outbox")
public class TriggerOutboxRelayProperties {

  /** Outbox 轮询间隔(ms)。默认 200。 */
  @Min(value = 1, message = "batch.trigger.outbox.poll-interval-millis must be at least 1")
  private long pollIntervalMillis = 200L;

  /** 单批最多扫多少条 outbox 记录。默认 256；Kafka ACK 与成功状态回写都按批处理。 */
  @Min(value = 1, message = "batch.trigger.outbox.batch-size must be at least 1")
  private int batchSize = 256;

  /** PUBLISHING 状态超时秒数,超时后回收为 NEW。默认 120。 */
  @Min(value = 1, message = "batch.trigger.outbox.publishing-timeout-seconds must be at least 1")
  private long publishingTimeoutSeconds = 120L;

  /** 单条最大发布尝试次数(达到后标 GIVE_UP)。默认 10。 */
  @Min(value = 1, message = "batch.trigger.outbox.max-publish-attempts must be at least 1")
  private int maxPublishAttempts = 10;

  /** 单个 Trigger 进程每秒最多向 Kafka 开始发布多少条事件。默认 40；0 表示关闭本地预算，仅用于受控环境。 */
  @Min(
      value = 0,
      message = "batch.trigger.outbox.max-publish-events-per-second must not be negative")
  private int maxPublishEventsPerSecond = 40;

  /**
   * Relay scheduler 是否在关闭时等待当前 poll 完成。
   *
   * <p>默认 false：trigger outbox 依靠 CAS、重试与 stale PUBLISHING 回收保证恢复；关闭期继续等待同步 Kafka send 可能拖住 JVM，并让
   * Redis/DB 进入 STOPPING 后被 poll 线程再次访问。
   */
  private boolean waitForTasksToCompleteOnShutdown = false;

  /** Relay scheduler 关闭等待秒数。默认 5；通常只用于等待线程响应 interrupt。 */
  @Min(value = 0, message = "batch.trigger.outbox.shutdown-await-seconds must not be negative")
  private int shutdownAwaitSeconds = 5;

  /**
   * Relay scheduler 的 SmartLifecycle phase。
   *
   * <p>Spring 停机时高 phase 先停。Redis LettuceConnectionFactory 默认 phase=0；这里显式高 phase， 保证 relay
   * 调度线程先取消并 drain，再销毁 Redis 连接。
   */
  private int schedulerPhase = BatchLifecyclePhases.MANAGED_SCHEDULER;
}
