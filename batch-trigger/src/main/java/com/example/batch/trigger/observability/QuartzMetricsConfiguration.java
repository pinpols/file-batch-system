package com.example.batch.trigger.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 启动期把 {@link QuartzMetricsListener} 注册到 {@link Scheduler}，并暴露 active triggers gauge。
 *
 * <p>开关：{@code batch.trigger.quartz-metrics.enabled}（默认 true）；关闭只在调试 / 极简启动场景使用。
 *
 * <p>Gauge 指标：
 *
 * <ul>
 *   <li>{@code batch.trigger.quartz.triggers.active} — Scheduler 当前已注册 JobKey 总数（按 group tag 分）；
 *       由 {@code MeterRegistry.gauge} 注册一个 supplier，Prometheus scrape 时即时执行。
 * </ul>
 *
 * <p>Counter / Timer 指标由 {@link QuartzMetricsListener} 在事件回调中递增。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "batch.trigger.quartz-metrics.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class QuartzMetricsConfiguration {

  private final Scheduler scheduler;
  private final MeterRegistry meterRegistry;

  @PostConstruct
  public void registerListenersAndGauges() throws SchedulerException {
    QuartzMetrics metrics = new QuartzMetrics(meterRegistry);
    QuartzMetricsListener listener = new QuartzMetricsListener(metrics);

    scheduler.getListenerManager().addJobListener(listener);
    scheduler.getListenerManager().addTriggerListener(listener);

    meterRegistry.gauge(
        QuartzMetrics.TRIGGERS_ACTIVE,
        Tags.of("group", "all"),
        scheduler,
        QuartzMetricsConfiguration::countAllJobs);

    log.info("Quartz metrics listener + active triggers gauge registered");
  }

  /**
   * 统计当前 Scheduler 已注册 Job 总数。Prometheus scrape 时调用，{@link SchedulerException}
   * 时返回 -1（Grafana 把负值标为不可用，避免脏数据）。
   */
  private static double countAllJobs(Scheduler scheduler) {
    try {
      return scheduler.getJobKeys(GroupMatcher.<JobKey>anyGroup()).size();
    } catch (SchedulerException e) {
      log.warn("count active triggers failed: {}", e.getMessage());
      return -1d;
    }
  }
}
