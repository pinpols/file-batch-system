package io.github.pinpols.batch.trigger.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Quartz JobStore 健康指标命名 + 注册中心。
 *
 * <p>暴露给 Prometheus / Grafana 的 4 类指标，用于 Quartz 拐点预警（详见 {@code
 * docs/architecture/quartz-replacement-evaluation.md} §7）：
 *
 * <ul>
 *   <li>{@code batch.trigger.quartz.fire.total} — Counter，每次 jobWasExecuted +1
 *   <li>{@code batch.trigger.quartz.execution.duration} — Timer，jobToBeExecuted → jobWasExecuted
 *       的总耗时；间接反映 acquire 锁等待 + DB 调度 + 业务执行时间
 *   <li>{@code batch.trigger.quartz.misfire.total} — Counter，每次 triggerMisfired +1
 *   <li>{@code batch.trigger.quartz.triggers.active} — Gauge，当前 enabled trigger 数（由 {@code
 *       QuartzMetricsConfiguration} 通过 {@link MeterRegistry#gauge} 直接注册，本类不持有）
 * </ul>
 *
 * <p>未暴露 {@code wal.byte.ratio}：该指标更适合通过 pg_exporter + Grafana 直接查 {@code pg_stat_database} 拿，不在
 * Java 侧维护。
 */
@Getter
@RequiredArgsConstructor
public class QuartzMetrics {

  private final MeterRegistry registry;

  public static final String FIRE_TOTAL = "batch.trigger.quartz.fire.total";
  public static final String EXECUTION_DURATION = "batch.trigger.quartz.execution.duration";
  public static final String MISFIRE_TOTAL = "batch.trigger.quartz.misfire.total";
  public static final String TRIGGERS_ACTIVE = "batch.trigger.quartz.triggers.active";

  Counter fireCounter(String jobGroup) {
    return Counter.builder(FIRE_TOTAL)
        .description("Quartz job fired (jobWasExecuted) — used to derive QPS via rate()")
        .tags(Tags.of("group", jobGroup))
        .register(registry);
  }

  Timer executionTimer(String jobGroup) {
    return Timer.builder(EXECUTION_DURATION)
        .description("Quartz job execution duration (jobToBeExecuted → jobWasExecuted)")
        .tags(Tags.of("group", jobGroup))
        .publishPercentiles(0.5, 0.95, 0.99)
        .publishPercentileHistogram(false)
        .register(registry);
  }

  Counter misfireCounter(String triggerGroup) {
    return Counter.builder(MISFIRE_TOTAL)
        .description("Quartz trigger misfired — used to detect schedule slippage / lock wait")
        .tags(Tags.of("group", triggerGroup))
        .register(registry);
  }

  void recordExecution(String jobGroup, long startNanos) {
    long elapsed = System.nanoTime() - startNanos;
    executionTimer(jobGroup).record(elapsed, TimeUnit.NANOSECONDS);
  }
}
