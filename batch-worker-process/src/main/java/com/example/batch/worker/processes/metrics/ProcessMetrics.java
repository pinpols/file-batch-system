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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * P2-4:PROCESS worker 关键运行指标。无 MeterRegistry 时全部退化为 no-op,本地 IDE 跑测试不需要 prometheus 也能编译通过。
 *
 * <ul>
 *   <li>{@code process_compute_staged_rows} - DistributionSummary,COMPUTE 写 staging 的行数(tag:
 *       tenantId)
 *   <li>{@code process_commit_published_rows} - DistributionSummary,COMMIT 落 target 表的行数(tag:
 *       tenantId)
 *   <li>{@code process_validation_failed_total} - Counter,VALIDATE 阶段单条 rule 失败计数(tag: tenantId,
 *       ruleName)
 *   <li>{@code process_stage_duration_seconds} - Timer,五段每段耗时(tag: stage, tenantId, success)
 * </ul>
 *
 * <p>tag 缓存:同 tag 组合复用同一个 meter 实例,避免每次 record 触发 registry 内部查找+构建。
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
  private final ConcurrentMap<String, DistributionSummary> stagedByTenant =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, DistributionSummary> publishedByTenant =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> validationFailedByKey = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Timer> stageTimerByKey = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> feedbackSwallowedByTenant =
      new ConcurrentHashMap<>();

  @Autowired
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
    String tenantTag = normalize(tenantId);
    DistributionSummary summary =
        stagedByTenant.computeIfAbsent(
            tenantTag,
            tag ->
                DistributionSummary.builder(STAGED_ROWS)
                    .description("PROCESS COMPUTE 写 staging 的行数")
                    .tags(Tags.of("tenantId", tag))
                    .register(registry));
    summary.record(stagedRows);
  }

  public void recordCommitPublishedRows(String tenantId, long publishedRows) {
    if (registry == null) {
      return;
    }
    String tenantTag = normalize(tenantId);
    DistributionSummary summary =
        publishedByTenant.computeIfAbsent(
            tenantTag,
            tag ->
                DistributionSummary.builder(PUBLISHED_ROWS)
                    .description("PROCESS COMMIT 落 target 表的行数")
                    .tags(Tags.of("tenantId", tag))
                    .register(registry));
    summary.record(publishedRows);
  }

  public void incrementValidationFailed(String tenantId, String ruleName) {
    if (registry == null) {
      return;
    }
    String tenantTag = normalize(tenantId);
    String ruleTag = normalize(ruleName);
    String key = tenantTag + "|" + ruleTag;
    Counter counter =
        validationFailedByKey.computeIfAbsent(
            key,
            k ->
                Counter.builder(VALIDATION_FAILED)
                    .description("PROCESS VALIDATE 阶段单条 rule 校验失败计数")
                    .tags(Tags.of("tenantId", tenantTag, "ruleName", ruleTag))
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
    String tenantTag = normalize(tenantId);
    Counter counter =
        feedbackSwallowedByTenant.computeIfAbsent(
            tenantTag,
            tag ->
                Counter.builder(FEEDBACK_SWALLOWED)
                    .description("PROCESS FEEDBACK 阶段吞掉的异常累计 (target 已落,但 cleanup/audit 失败)")
                    .tags(Tags.of("tenantId", tag))
                    .register(registry));
    counter.increment();
  }

  public void recordStageDuration(
      String stage, String tenantId, boolean success, long durationNanos) {
    if (registry == null) {
      return;
    }
    String stageTag = normalize(stage);
    String tenantTag = normalize(tenantId);
    String successTag = success ? "true" : "false";
    String key = stageTag + "|" + tenantTag + "|" + successTag;
    Timer timer =
        stageTimerByKey.computeIfAbsent(
            key,
            k ->
                Timer.builder(STAGE_DURATION)
                    .description("PROCESS 五段每段耗时")
                    .tags(Tags.of("stage", stageTag, "tenantId", tenantTag, "success", successTag))
                    .register(registry));
    timer.record(durationNanos, TimeUnit.NANOSECONDS);
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? UNKNOWN_TAG : value;
  }
}
