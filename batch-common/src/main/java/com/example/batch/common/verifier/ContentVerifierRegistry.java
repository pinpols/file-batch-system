package com.example.batch.common.verifier;

import com.example.batch.common.enums.JobType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 收集所有 {@link ContentVerifier} bean，并按 jobType+stageCode 路由。
 *
 * <p>由调用方（如 worker stage hook）调 {@link #verifiersFor(JobType, String)} 拿到适配集合， 再调 {@link
 * #run(ContentVerifier, VerifyContext)} 执行单个 verifier —— 后者负责包 Micrometer 计时与错误吞咽。
 *
 * <h2>失败处理</h2>
 *
 * 单个 verifier 抛出异常不会中断后续 verifier；异常 → 记一条 Counter（reason=EXCEPTION） + WARN 日志，并把异常摘要写进返回的 {@link
 * VerifyResult.evidence}，让调用方可以选择继续。
 *
 * <h2>Metrics（{@link io.micrometer.core.instrument.MeterRegistry} 缺席时全部 no-op）</h2>
 *
 * <ul>
 *   <li>{@code batch.verifier.duration{code,outcome=pass|fail|error}} Timer
 *   <li>{@code batch.verifier.failures{code,reason}} Counter（reason = code 中的失败码 / EXCEPTION）
 * </ul>
 */
@Component
@Slf4j
public class ContentVerifierRegistry {

  private static final String METRIC_DURATION = "batch.verifier.duration";
  private static final String METRIC_FAILURES = "batch.verifier.failures";

  private final List<ContentVerifier> verifiers;
  private final MeterRegistry meterRegistry;
  private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> failureCounterCache = new ConcurrentHashMap<>();

  public ContentVerifierRegistry(
      ObjectProvider<ContentVerifier> verifierProvider,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.verifiers = verifierProvider.orderedStream().toList();
    this.meterRegistry = meterRegistryProvider.getIfAvailable();
    if (log.isInfoEnabled()) {
      log.info(
          "ContentVerifierRegistry initialized with {} verifier(s): {}",
          verifiers.size(),
          verifiers.stream().map(ContentVerifier::code).toList());
    }
  }

  /** 列出对当前 jobType+stageCode 适用的 verifier；null stageCode 视为通配。 */
  public List<ContentVerifier> verifiersFor(JobType jobType, String stageCode) {
    Objects.requireNonNull(jobType, "jobType");
    return verifiers.stream()
        .filter(v -> v.appliesTo() != null && v.appliesTo().contains(jobType))
        .filter(v -> v.stageCode() == null || v.stageCode().equalsIgnoreCase(stageCode))
        .toList();
  }

  /** 执行单个 verifier，包 Micrometer 计时与异常吞咽。永远返回非空 {@link VerifyResult}；调用方据此聚合。 */
  public VerifyResult run(ContentVerifier verifier, VerifyContext context) {
    long startNanos = System.nanoTime();
    String code = verifier.code();
    VerifyResult result;
    String outcome;
    try {
      result = verifier.verify(context);
      if (result == null) {
        result = VerifyResult.fail(code + "_NULL_RESULT", "verifier returned null");
      }
      outcome = result.passed() ? "pass" : "fail";
    } catch (RuntimeException ex) {
      log.warn(
          "ContentVerifier {} threw exception: tenantId={}, taskId={}, stage={}",
          code,
          context.tenantId(),
          context.taskId(),
          context.stageCode(),
          ex);
      result =
          VerifyResult.fail(
              code + "_EXCEPTION",
              ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
              Map.of("exception", ex.getClass().getSimpleName()));
      outcome = "error";
    }
    recordMetrics(code, outcome, result, startNanos);
    return result;
  }

  private void recordMetrics(String code, String outcome, VerifyResult result, long startNanos) {
    if (meterRegistry == null) {
      return;
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    Timer timer =
        timerCache.computeIfAbsent(
            code + "::" + outcome,
            key ->
                Timer.builder(METRIC_DURATION)
                    .description("ContentVerifier execution time per code/outcome")
                    .tags(Tags.of("code", code, "outcome", outcome))
                    .register(meterRegistry));
    timer.record(Duration.ofNanos(elapsedNanos));

    if (!result.passed()) {
      String reason = result.code() == null ? "UNKNOWN" : result.code();
      Counter counter =
          failureCounterCache.computeIfAbsent(
              code + "::" + reason,
              key ->
                  Counter.builder(METRIC_FAILURES)
                      .description("ContentVerifier failure count per code/reason")
                      .tags(Tags.of("code", code, "reason", reason))
                      .register(meterRegistry));
      counter.increment();
    }
  }
}
