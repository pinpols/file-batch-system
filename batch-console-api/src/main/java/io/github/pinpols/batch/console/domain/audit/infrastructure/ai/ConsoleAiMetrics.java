package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Console AI 成本 / 调用可观测指标。
 *
 * <p>成本的<b>可聚合总量</b>走这里的低基数 Micrometer 指标；成本的<b>租户维度</b>不进 metric tag(高基数会打爆监控,见 #782/#788),而是落
 * {@code console_ai_audit_log} 的 prompt_tokens/completion_tokens 列, 每租户成本靠审计表事后 SQL 聚合。
 *
 * <ul>
 *   <li>{@code batch.console.ai.tokens.total{type=prompt|completion}} — 累计 token 消耗(成本可观测)。
 *   <li>{@code batch.console.ai.requests.total{decision=approved|rejected|rate_limited|failed}} —
 *       调用结果分布(低基数 4 值)。
 * </ul>
 */
@Component
public class ConsoleAiMetrics {

  static final String TOKENS_METRIC = "batch.console.ai.tokens.total";
  static final String REQUESTS_METRIC = "batch.console.ai.requests.total";

  /** 门禁放行 + 模型成功应答。 */
  public static final String DECISION_APPROVED = "approved";

  /** 被提示词门禁(safety / scope / disabled)拒绝。 */
  public static final String DECISION_REJECTED = "rejected";

  /** 命中调用限流被拒(429)。 */
  public static final String DECISION_RATE_LIMITED = "rate_limited";

  /** 模型调用失败 / 超时 → 优雅降级。 */
  public static final String DECISION_FAILED = "failed";

  private final MeterRegistry meterRegistry;

  public ConsoleAiMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** 记录一次调用消耗的 token(仅成功调用有值；null / 非正数忽略)。 */
  public void recordTokens(Integer promptTokens, Integer completionTokens) {
    if (promptTokens != null && promptTokens > 0) {
      tokenCounter("prompt").increment(promptTokens);
    }
    if (completionTokens != null && completionTokens > 0) {
      tokenCounter("completion").increment(completionTokens);
    }
  }

  /** 记录一次调用的结果分布(低基数 decision tag,绝不带 tenant)。 */
  public void recordDecision(String decision) {
    meterRegistry.counter(REQUESTS_METRIC, "decision", decision).increment();
  }

  private Counter tokenCounter(String type) {
    return meterRegistry.counter(TOKENS_METRIC, "type", type);
  }
}
