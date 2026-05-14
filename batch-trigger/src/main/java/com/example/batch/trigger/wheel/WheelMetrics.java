package com.example.batch.trigger.wheel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/**
 * 时间轮 trigger scheduler 的 micrometer 指标(详见 quartz-replacement-design.md §13)。
 *
 * <p>暴露:
 *
 * <ul>
 *   <li>{@code batch.trigger.wheel.tasks.scheduled} — Gauge,当前 wheel 内 task 数(供应方注册)
 *   <li>{@code batch.trigger.wheel.fire.lag} — Timer,实际 fire - 预期 fire(ms)
 *   <li>{@code batch.trigger.wheel.scan.duration} — Timer,slidingWindow 扫库耗时
 *   <li>{@code batch.trigger.wheel.scan.task.count} — Counter,扫库 push 的 task 数(累计)
 *   <li>{@code batch.trigger.wheel.fire.success} — Counter,成功 fire 数
 *   <li>{@code batch.trigger.wheel.fire.failed} — Counter,LaunchService 调用失败数
 *   <li>{@code batch.trigger.wheel.fire.duplicate.skipped} — Counter,DB UNIQUE 兜住的重复 fire 数(R-1)
 *   <li>{@code batch.trigger.wheel.misfire.handled} — Counter (按 policy tag),misfire 处理数
 *   <li>{@code batch.trigger.wheel.leader.acquire} — Counter,leader 切换次数
 *   <li>{@code batch.trigger.wheel.leader.acquire.duration} — Timer,fast-path 耗时
 *   <li>{@code batch.trigger.wheel.runtime_state.stale_marker.released} — Counter,接管的 stale marker
 *       数
 * </ul>
 */
@RequiredArgsConstructor
public class WheelMetrics {

  private final MeterRegistry registry;

  public static final String TASKS_SCHEDULED = "batch.trigger.wheel.tasks.scheduled";
  public static final String FIRE_LAG = "batch.trigger.wheel.fire.lag";
  public static final String SCAN_DURATION = "batch.trigger.wheel.scan.duration";
  public static final String SCAN_TASK_COUNT = "batch.trigger.wheel.scan.task.count";
  public static final String FIRE_SUCCESS = "batch.trigger.wheel.fire.success";
  public static final String FIRE_FAILED = "batch.trigger.wheel.fire.failed";
  public static final String FIRE_DUPLICATE_SKIPPED = "batch.trigger.wheel.fire.duplicate.skipped";
  public static final String MISFIRE_HANDLED = "batch.trigger.wheel.misfire.handled";
  public static final String LEADER_ACQUIRE = "batch.trigger.wheel.leader.acquire";
  public static final String LEADER_ACQUIRE_DURATION =
      "batch.trigger.wheel.leader.acquire.duration";
  public static final String STALE_MARKER_RELEASED =
      "batch.trigger.wheel.runtime_state.stale_marker.released";

  /** Gauge:当前 wheel 内 task 数;由 scheduler 提供 supplier。 */
  public void registerTasksScheduledGauge(Supplier<Number> supplier) {
    io.micrometer.core.instrument.Gauge.builder(TASKS_SCHEDULED, supplier)
        .description("current wheel scheduled task count")
        .register(registry);
  }

  public void recordFireLag(long lagMillis) {
    Timer.builder(FIRE_LAG)
        .description("actual fire time - scheduled fire time")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)
        .record(lagMillis, TimeUnit.MILLISECONDS);
  }

  public Timer scanDuration() {
    return Timer.builder(SCAN_DURATION)
        .description("slidingWindow scan duration including DB query + claim")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry);
  }

  public void incrementScanTaskCount(long delta) {
    Counter.builder(SCAN_TASK_COUNT)
        .description("cumulative tasks pushed into wheel by sliding window scan")
        .register(registry)
        .increment(delta);
  }

  public void incrementFireSuccess(String jobGroup) {
    Counter.builder(FIRE_SUCCESS).tags(Tags.of("group", jobGroup)).register(registry).increment();
  }

  public void incrementFireFailed(String jobGroup, String reason) {
    Counter.builder(FIRE_FAILED)
        .tags(Tags.of("group", jobGroup, "reason", reason))
        .register(registry)
        .increment();
  }

  public void incrementFireDuplicateSkipped(String jobGroup) {
    Counter.builder(FIRE_DUPLICATE_SKIPPED)
        .description("DB UNIQUE constraint blocked duplicate fire (R-1 defense)")
        .tags(Tags.of("group", jobGroup))
        .register(registry)
        .increment();
  }

  public void incrementMisfireHandled(String policy) {
    Counter.builder(MISFIRE_HANDLED).tags(Tags.of("policy", policy)).register(registry).increment();
  }

  public void incrementLeaderAcquire() {
    Counter.builder(LEADER_ACQUIRE).register(registry).increment();
  }

  public Timer leaderAcquireDuration() {
    return Timer.builder(LEADER_ACQUIRE_DURATION).register(registry);
  }

  public void incrementStaleMarkerReleased(long count) {
    Counter.builder(STALE_MARKER_RELEASED).register(registry).increment(count);
  }

  /**
   * R2-P0-3: advanceAfterFire DB 失败计数；持续增长表示 wheel 处于"成功 fire 但 next_fire_time 没推进" 的危险态——会被下一 tick
   * 重复 fire，触发条件通常是 DB 短暂不可达 / 锁等待超时。
   */
  public void incrementAdvanceFailed(String jobCode) {
    Counter.builder("batch.trigger.wheel.advance.failed.total")
        .description(
            "advanceAfterFire DB update failed; next_fire_time may be stale → re-fire risk")
        .tags(Tags.of("jobCode", jobCode == null ? "unknown" : jobCode))
        .register(registry)
        .increment();
  }
}
