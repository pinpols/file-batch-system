package com.example.batch.trigger.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Trigger;
import org.quartz.TriggerListener;

/**
 * Quartz JobListener + TriggerListener，记录 fire / misfire / 执行耗时三类指标。
 *
 * <p>{@code start} 时间戳从 {@code jobToBeExecuted} 起算，写入 {@link #startTimes} ConcurrentMap；
 * {@code jobWasExecuted} 时取出并 record 到 timer，然后从 map 移除避免内存泄漏。
 *
 * <p>同名 JobKey 不会并发触发（Quartz 默认 @DisallowConcurrentExecution + 状态机），所以
 * map 里同 key 不会被覆盖；即使覆盖了也只是丢一次执行的耗时记录，不影响语义。
 */
@Slf4j
@RequiredArgsConstructor
public class QuartzMetricsListener implements JobListener, TriggerListener {

  static final String NAME = "quartzMetricsListener";

  private final QuartzMetrics metrics;
  private final ConcurrentMap<JobKey, Long> startTimes = new ConcurrentHashMap<>();

  @Override
  public String getName() {
    return NAME;
  }

  // ── JobListener ─────────────────────────────────────────────

  @Override
  public void jobToBeExecuted(JobExecutionContext context) {
    startTimes.put(context.getJobDetail().getKey(), System.nanoTime());
  }

  @Override
  public void jobExecutionVetoed(JobExecutionContext context) {
    // veto 路径不记 fire，但要清掉 start 防泄漏
    startTimes.remove(context.getJobDetail().getKey());
  }

  @Override
  public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
    JobKey key = context.getJobDetail().getKey();
    Long start = startTimes.remove(key);
    String group = key.getGroup();
    metrics.fireCounter(group).increment();
    if (start != null) {
      metrics.recordExecution(group, start);
    }
  }

  // ── TriggerListener ─────────────────────────────────────────

  @Override
  public void triggerFired(Trigger trigger, JobExecutionContext context) {
    // 不在这里计 fire — 用 jobWasExecuted 计，因为 trigger fire 后可能被 veto
  }

  @Override
  public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
    return false;
  }

  @Override
  public void triggerMisfired(Trigger trigger) {
    metrics.misfireCounter(trigger.getKey().getGroup()).increment();
  }

  @Override
  public void triggerComplete(
      Trigger trigger,
      JobExecutionContext context,
      Trigger.CompletedExecutionInstruction triggerInstructionCode) {
    // 不需要在 trigger 端再记一次
  }
}
