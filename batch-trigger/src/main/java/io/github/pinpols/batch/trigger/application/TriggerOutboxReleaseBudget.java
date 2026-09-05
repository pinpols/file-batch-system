package io.github.pinpols.batch.trigger.application;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import java.util.function.LongSupplier;

/**
 * 单个 Trigger 进程向下游释放事件的固定窗口预算。
 *
 * <p>Trigger API 已成功持久化的请求必须先留在 outbox；当 orchestrator 和 worker 的持续处理能力低于入口突发时，
 * 不能继续无界地把事件推向下游数据库。预算只决定一秒内最多开始多少次发布，不影响失败重试、CAS 状态迁移或消息幂等键。
 *
 * <p>计数器位于 JVM 内存，故限额是单进程语义。默认 Helm 部署为单 Trigger 副本；扩为多个副本前，必须改为
 * 共享预算或相应下调每副本限额，不能把本值当作集群总预算。
 */
final class TriggerOutboxReleaseBudget {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final TriggerOutboxRelayProperties properties;
  private final LongSupplier epochSecondSupplier;
  private final LongSupplier monotonicNanosSupplier;

  private long windowEpochSecond = Long.MIN_VALUE;
  private int reservedInWindow;
  private long lastPacedReservationNanos = Long.MIN_VALUE;
  /** 令牌以纳秒为单位：1 个完整令牌 = {@link #NANOS_PER_SECOND}。 */
  private long pacedAvailableTokenNanos;

  TriggerOutboxReleaseBudget(TriggerOutboxRelayProperties properties) {
    this(properties, () -> System.currentTimeMillis() / 1_000L, System::nanoTime);
  }

  TriggerOutboxReleaseBudget(
      TriggerOutboxRelayProperties properties, LongSupplier epochSecondSupplier) {
    this(properties, epochSecondSupplier, System::nanoTime);
  }

  TriggerOutboxReleaseBudget(
      TriggerOutboxRelayProperties properties,
      LongSupplier epochSecondSupplier,
      LongSupplier monotonicNanosSupplier) {
    this.properties = properties;
    this.epochSecondSupplier = epochSecondSupplier;
    this.monotonicNanosSupplier = monotonicNanosSupplier;
  }

  /**
   * 预留本轮可以发布的事件数。0 表示本秒预算已耗尽；0 配置值表示不启用限速，仅用于受控压测或外部背压已生效的部署。
   */
  synchronized int reserve(int requested) {
    if (requested <= 0) {
      return 0;
    }
    int limit = properties.getMaxPublishEventsPerSecond();
    if (limit <= 0) {
      return requested;
    }
    resetWindowIfNeeded();
    int granted = Math.min(requested, Math.max(0, limit - reservedInWindow));
    reservedInWindow += granted;
    return granted;
  }

  /**
   * 按两次 relay poll 的实际时间间隔领取本轮预算。
   *
   * <p>{@code scheduleWithFixedDelay} 的下一轮会在上一轮 DB/Kafka 工作结束后才计时。若仍按配置的 poll
   * 间隔固定领取，实际周期略慢于配置时，秒窗口末尾会留下永远用不到的额度。这里使用容量为一秒配额的令牌桶，
   * 以单调时钟持续补充令牌：调度抖动不会损失长期吞吐，暂停后的追回也不会超过一个完整秒配额。
   */
  synchronized int reserveAtPace(int batchSize, long configuredPollIntervalMillis) {
    if (batchSize <= 0) {
      return 0;
    }
    int limit = properties.getMaxPublishEventsPerSecond();
    if (limit <= 0) {
      return batchSize;
    }

    long nowNanos = monotonicNanosSupplier.getAsLong();
    long previousNanos = lastPacedReservationNanos;
    lastPacedReservationNanos = nowNanos;
    long tokenCapacityNanos = (long) limit * NANOS_PER_SECOND;
    if (previousNanos == Long.MIN_VALUE || nowNanos <= previousNanos) {
      long initialIntervalNanos = configuredPollIntervalMillis >= 1_000L
          ? NANOS_PER_SECOND
          : Math.max(1L, configuredPollIntervalMillis) * 1_000_000L;
      pacedAvailableTokenNanos = Math.min(tokenCapacityNanos, initialIntervalNanos * limit);
    } else {
      long elapsedNanos = nowNanos - previousNanos;
      long refillNanos =
          elapsedNanos >= NANOS_PER_SECOND ? tokenCapacityNanos : elapsedNanos * limit;
      pacedAvailableTokenNanos =
          Math.min(tokenCapacityNanos, pacedAvailableTokenNanos + refillNanos);
    }
    int granted = (int) Math.min(batchSize, pacedAvailableTokenNanos / NANOS_PER_SECOND);
    pacedAvailableTokenNanos -= (long) granted * NANOS_PER_SECOND;
    resetWindowIfNeeded();
    reservedInWindow += granted;
    return granted;
  }

  synchronized int reservedInCurrentWindow() {
    if (properties.getMaxPublishEventsPerSecond() <= 0) {
      return 0;
    }
    resetWindowIfNeeded();
    return reservedInWindow;
  }

  private void resetWindowIfNeeded() {
    long now = epochSecondSupplier.getAsLong();
    if (now != windowEpochSecond) {
      windowEpochSecond = now;
      reservedInWindow = 0;
    }
  }
}
