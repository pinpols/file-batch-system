package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import jakarta.validation.constraints.AssertTrue;
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

  /** 是否根据 orchestrator trigger consumer lag 自适应收缩 Relay 发布速率。默认关闭，需灰度启用。 */
  private boolean adaptiveReleaseEnabled = false;

  /** 自适应模式下仍保留的最小发布速率，避免瞬时观测故障让链路永久停滞。 */
  @Min(value = 1, message = "batch.trigger.outbox.min-publish-events-per-second must be at least 1")
  private int minPublishEventsPerSecond = 5;

  /** consumer lag 低于该值时逐步恢复发布速率。 */
  @Min(value = 0, message = "batch.trigger.outbox.lag-soft-threshold must not be negative")
  private long lagSoftThreshold = 1_000L;

  /** consumer lag 达到该值时将当前发布速率减半。 */
  @Min(value = 1, message = "batch.trigger.outbox.lag-hard-threshold must be at least 1")
  private long lagHardThreshold = 5_000L;

  /** lag 恢复后每个新样本增加的发布速率。 */
  @Min(value = 1, message = "batch.trigger.outbox.adaptive-increase-step must be at least 1")
  private int adaptiveIncreaseStep = 1;

  /** Kafka consumer lag 采样间隔。 */
  @Min(
      value = 1000,
      message = "batch.trigger.outbox.lag-sample-interval-millis must be at least 1000")
  private long lagSampleIntervalMillis = 5_000L;

  /** 单次 Kafka Admin 查询超时。 */
  @Min(value = 100, message = "batch.trigger.outbox.lag-query-timeout-millis must be at least 100")
  private long lagQueryTimeoutMillis = 2_000L;

  /** 被采样的 orchestrator consumer group。 */
  private String consumerGroupId = "orchestrator-trigger-launch";

  @AssertTrue(message = "batch.trigger.outbox adaptive release thresholds are inconsistent")
  public boolean isAdaptiveReleaseConfigurationValid() {
    if (!adaptiveReleaseEnabled) {
      return true;
    }
    return maxPublishEventsPerSecond > 0
        && minPublishEventsPerSecond <= maxPublishEventsPerSecond
        && lagSoftThreshold < lagHardThreshold
        && consumerGroupId != null
        && !consumerGroupId.isBlank();
  }

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
