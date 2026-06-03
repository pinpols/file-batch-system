package com.example.batch.worker.processes.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * P2-4:PROCESS worker 关键运行指标。无 MeterRegistry 时全部退化为 no-op,本地 IDE 跑测试不需要 prometheus 也能编译通过。
 *
 * <ul>
 *   <li>{@code process_compute_staged_rows} - DistributionSummary,COMPUTE 写 staging 的行数
 *   <li>{@code process_commit_published_rows} - DistributionSummary,COMMIT 落 target 表的行数
 *   <li>{@code process_validation_failed_total} - Counter,VALIDATE 阶段单条 rule 失败计数(tag: ruleName)
 *   <li>{@code process_stage_duration_seconds} - Timer,五段每段耗时(tag: stage, success)
 *   <li>{@code process_feedback_swallowed_total} - Counter,FEEDBACK 阶段吞掉的异常累计
 * </ul>
 *
 * <p><b>tenantId 不作为 Micrometer tag</b>:运行时高基数(随租户数线性增长)会让 Prometheus time-series 内存爆炸。tenantId 走
 * MDC 进日志便于按租户追溯;按租维度聚合改用 Prometheus exemplar 或日志聚合方案, 不在 metrics label 维度直接展开。
 *
 * <p>方法签名保留 tenantId 入参纯为向后兼容,内部不再用于 tag 缓存键。
 */
@Component
public class ProcessMetrics {

  static final String STAGED_ROWS = "process_compute_staged_rows";
  static final String PUBLISHED_ROWS = "process_commit_published_rows";
  static final String VALIDATION_FAILED = "process_validation_failed_total";
  static final String STAGE_DURATION = "process_stage_duration_seconds";
  static final String FEEDBACK_SWALLOWED = "process_feedback_swallowed_total";

  private static final String UNKNOWN_TAG = "unknown";

  private final MeterRegistry registry;
  // 单例 meter(不再按租户拆 — tenantId 已从 tag 去掉)
  private volatile DistributionSummary stagedSummary;
  private volatile DistributionSummary publishedSummary;
  private volatile Counter feedbackSwallowedCounter;
  // ruleName 是 低基数(规则定义有限,通常 <100),保留为 tag
  private final ConcurrentMap<String, Counter> validationFailedByRule = new ConcurrentHashMap<>();
  // stage + success 都是 低基数,组合 < 12 种
  private final ConcurrentMap<String, Timer> stageTimerByKey = new ConcurrentHashMap<>();

  // 显式 @Autowired:类内有 2 个构造器(public + private),Spring 4.3+ "exactly
  // one constructor" 自动推断不成立,必须显式标主装配 ctor;CLAUDE.md §Java #3 豁免
  // 构造器上的 @Autowired(只禁 field/setter)。
  @org.springframework.beans.factory.annotation.Autowired
  public ProcessMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.registry = meterRegistryProvider.getIfAvailable();
  }

  private ProcessMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** 测试 / 单测组装用:无 MeterRegistry,所有 record 调用都是 no-op。 */
  public static ProcessMetrics noop() {
    return new ProcessMetrics((MeterRegistry) null);
  }

  public void recordComputeStagedRows(String tenantId, long stagedRows) {
    if (registry == null) {
      return;
    }
    if (stagedSummary == null) {
      synchronized (this) {
        if (stagedSummary == null) {
          stagedSummary =
              DistributionSummary.builder(STAGED_ROWS)
                  .description("PROCESS COMPUTE 写 staging 的行数")
                  .register(registry);
        }
      }
    }
    stagedSummary.record(stagedRows);
  }

  public void recordCommitPublishedRows(String tenantId, long publishedRows) {
    if (registry == null) {
      return;
    }
    if (publishedSummary == null) {
      synchronized (this) {
        if (publishedSummary == null) {
          publishedSummary =
              DistributionSummary.builder(PUBLISHED_ROWS)
                  .description("PROCESS COMMIT 落 target 表的行数")
                  .register(registry);
        }
      }
    }
    publishedSummary.record(publishedRows);
  }

  public void incrementValidationFailed(String tenantId, String ruleName) {
    if (registry == null) {
      return;
    }
    String ruleTag = normalize(ruleName);
    Counter counter =
        validationFailedByRule.computeIfAbsent(
            ruleTag,
            tag ->
                Counter.builder(VALIDATION_FAILED)
                    .description("PROCESS VALIDATE 阶段单条 rule 校验失败计数")
                    .tags(Tags.of("ruleName", tag))
                    .register(registry));
    counter.increment();
  }

  /**
   * FEEDBACK 阶段吞掉的异常计数。按设计,FEEDBACK 失败不应让整个 task 失败(target 已落 / staging 已清),但 完全静默会让 staging 残留 /
   * 审计漏写不可见。本指标暴露 swallow 频率,告警 / 排障的入口。
   */
  public void incrementFeedbackSwallowed(String tenantId) {
    if (registry == null) {
      return;
    }
    if (feedbackSwallowedCounter == null) {
      synchronized (this) {
        if (feedbackSwallowedCounter == null) {
          feedbackSwallowedCounter =
              Counter.builder(FEEDBACK_SWALLOWED)
                  .description("PROCESS FEEDBACK 阶段吞掉的异常累计 (target 已落,但 cleanup/audit 失败)")
                  .register(registry);
        }
      }
    }
    feedbackSwallowedCounter.increment();
  }

  public void recordStageDuration(
      String stage, String tenantId, boolean success, long durationNanos) {
    if (registry == null) {
      return;
    }
    String stageTag = normalize(stage);
    String successTag = success ? "true" : "false";
    String key = stageTag + "|" + successTag;
    Timer timer =
        stageTimerByKey.computeIfAbsent(
            key,
            k ->
                Timer.builder(STAGE_DURATION)
                    .description("PROCESS 五段每段耗时")
                    .tags(Tags.of("stage", stageTag, "success", successTag))
                    .register(registry));
    timer.record(durationNanos, TimeUnit.NANOSECONDS);
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? UNKNOWN_TAG : value;
  }
}
