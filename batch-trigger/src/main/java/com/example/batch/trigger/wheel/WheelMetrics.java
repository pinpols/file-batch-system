package com.example.batch.trigger.wheel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
 *
 * <p><b>性能</b>:fire 热路径 Timer/Counter 在构造时一次性 register 为 field,避免每次调用 {@code
 * Timer.builder(...).register(registry)} 重复 map lookup + Builder 对象分配。 带 tag 的
 * Counter(group/reason/policy/jobCode)按 tag key 缓存到 ConcurrentMap。
 */
public class WheelMetrics {

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
  public static final String ADVANCE_FAILED = "batch.trigger.wheel.advance.failed.total";

  private final MeterRegistry registry;

  // ── 热路径预注册的无 tag meter ─────────────────────────────────
  private final Timer fireLagTimer;
  private final Timer scanDurationTimer;
  private final Counter scanTaskCounter;
  private final Counter leaderAcquireCounter;
  private final Timer leaderAcquireDurationTimer;
  private final Counter staleMarkerReleasedCounter;

  // ── 带 tag 的 meter,按 tag key 缓存 ─────────────────────────────
  private final ConcurrentMap<String, Counter> fireSuccessByGroup = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> fireFailedByGroupReason = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> fireDuplicateSkippedByGroup =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> misfireHandledByPolicy = new ConcurrentHashMap<>();

  // ADR/audit 2026-06-03:advance.failed 移除 jobCode tag(高基数 = 租户 × 作业,
  // Prometheus series 爆炸风险);改全局 counter,具体 jobCode 走日志/trace 关联.
  private final Counter advanceFailedCounter;

  public WheelMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.fireLagTimer =
        Timer.builder(FIRE_LAG)
            .description("actual fire time - scheduled fire time")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    this.scanDurationTimer =
        Timer.builder(SCAN_DURATION)
            .description("slidingWindow scan duration including DB query + claim")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    this.scanTaskCounter =
        Counter.builder(SCAN_TASK_COUNT)
            .description("cumulative tasks pushed into wheel by sliding window scan")
            .register(registry);
    this.leaderAcquireCounter = Counter.builder(LEADER_ACQUIRE).register(registry);
    this.leaderAcquireDurationTimer = Timer.builder(LEADER_ACQUIRE_DURATION).register(registry);
    this.staleMarkerReleasedCounter = Counter.builder(STALE_MARKER_RELEASED).register(registry);
    this.advanceFailedCounter =
        Counter.builder(ADVANCE_FAILED)
            .description(
                "advanceAfterFire DB update failed; next_fire_time may be stale → re-fire risk")
            .register(registry);
  }

  /** P0 (audit 2026-05-23):fire 异步池队列堆积监控。Gauge,supplier 由 executor bean 提供。 */
  public static final String FIRE_QUEUE_SIZE = "batch.trigger.fire.queue.size";

  /** P0 (audit 2026-05-23):单次 fire 异步执行耗时(loadDescriptor + launchScheduled + advance)。 */
  public static final String FIRE_ASYNC_DURATION = "batch.trigger.fire.async.duration";

  /** Gauge:当前 wheel 内 task 数;由 scheduler 提供 supplier。 */
  public void registerTasksScheduledGauge(Supplier<Number> supplier) {
    io.micrometer.core.instrument.Gauge.builder(TASKS_SCHEDULED, supplier)
        .description("current wheel scheduled task count")
        .register(registry);
  }

  /** Gauge:triggerFireExecutor 队列堆积数;由 executor bean 提供 supplier。 */
  public void registerFireQueueSizeGauge(Supplier<Number> supplier) {
    io.micrometer.core.instrument.Gauge.builder(FIRE_QUEUE_SIZE, supplier)
        .description("triggerFireExecutor queue depth — high values signal wheel back-pressure")
        .register(registry);
  }

  /** Timer:单次 fire 异步执行耗时。 */
  public Timer fireAsyncDuration() {
    return Timer.builder(FIRE_ASYNC_DURATION)
        .description("end-to-end fire() runtime on async executor")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry);
  }

  public void recordFireLag(long lagMillis) {
    fireLagTimer.record(lagMillis, TimeUnit.MILLISECONDS);
  }

  public Timer scanDuration() {
    return scanDurationTimer;
  }

  public void incrementScanTaskCount(long delta) {
    scanTaskCounter.increment(delta);
  }

  public void incrementFireSuccess(String jobGroup) {
    fireSuccessByGroup
        .computeIfAbsent(
            jobGroup,
            g -> Counter.builder(FIRE_SUCCESS).tags(Tags.of("group", g)).register(registry))
        .increment();
  }

  public void incrementFireFailed(String jobGroup, String reason) {
    String key = jobGroup + "" + reason;
    fireFailedByGroupReason
        .computeIfAbsent(
            key,
            _ ->
                Counter.builder(FIRE_FAILED)
                    .tags(Tags.of("group", jobGroup, "reason", reason))
                    .register(registry))
        .increment();
  }

  public void incrementFireDuplicateSkipped(String jobGroup) {
    fireDuplicateSkippedByGroup
        .computeIfAbsent(
            jobGroup,
            g ->
                Counter.builder(FIRE_DUPLICATE_SKIPPED)
                    .description("DB UNIQUE constraint blocked duplicate fire (R-1 defense)")
                    .tags(Tags.of("group", g))
                    .register(registry))
        .increment();
  }

  public void incrementMisfireHandled(String policy) {
    misfireHandledByPolicy
        .computeIfAbsent(
            policy,
            p -> Counter.builder(MISFIRE_HANDLED).tags(Tags.of("policy", p)).register(registry))
        .increment();
  }

  public static final String READINESS_DEFERRED = "batch.trigger.wheel.readiness.deferred";
  public static final String READINESS_TIMEOUT = "batch.trigger.wheel.readiness.timeout";

  private final ConcurrentMap<String, Counter> readinessDeferredByGroup = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> readinessTimeoutByGroup = new ConcurrentHashMap<>();

  /** ADR-043:依赖未就绪、窗口内 defer 重检一次(同一 bizDate 不丢批)。 */
  public void incrementReadinessDeferred(String jobGroup) {
    readinessDeferredByGroup
        .computeIfAbsent(
            jobGroup,
            g ->
                Counter.builder(READINESS_DEFERRED)
                    .description(
                        "upstream not ready; fire deferred within readinessWindow (ADR-043)")
                    .tags(Tags.of("group", g))
                    .register(registry))
        .increment();
  }

  /** ADR-043:等待超 readinessWindow 仍未就绪,放弃本 bizDate(已 ERROR 告警)。运维应关注此 counter。 */
  public void incrementReadinessTimeout(String jobGroup) {
    readinessTimeoutByGroup
        .computeIfAbsent(
            jobGroup,
            g ->
                Counter.builder(READINESS_TIMEOUT)
                    .description(
                        "upstream not ready past readinessWindow; gave up this bizDate (ADR-043)")
                    .tags(Tags.of("group", g))
                    .register(registry))
        .increment();
  }

  public void incrementLeaderAcquire() {
    leaderAcquireCounter.increment();
  }

  public Timer leaderAcquireDuration() {
    return leaderAcquireDurationTimer;
  }

  public void incrementStaleMarkerReleased(long count) {
    staleMarkerReleasedCounter.increment(count);
  }

  /**
   * R2-P0-3: advanceAfterFire DB 失败计数；持续增长表示 wheel 处于"成功 fire 但 next_fire_time 没推进" 的危险态——会被下一 tick
   * 重复 fire，触发条件通常是 DB 短暂不可达 / 锁等待超时。
   *
   * <p>audit 2026-06-03:已移除 jobCode tag(高基数 = 租户 × 作业,Prometheus series 爆炸风险);jobCode 入参保留 用作未来
   * log/trace 钩子,当前仅用于告警全局聚合,具体作业定位走日志/trace 关联(MDC + traceId)。
   */
  public void incrementAdvanceFailed(String jobCode) {
    advanceFailedCounter.increment();
  }
}
