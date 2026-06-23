package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * tenant_quota_policy.exceeded_strategy 字典 (V89)。
 *
 * <p>quota 触顶（tenant 级 / fair-share group / queue 级）时的处置语义。orchestrator 的 {@code
 * DefaultConcurrencyLimiter} + {@code DefaultResourceScheduler} 联合实现：
 *
 * <ul>
 *   <li>{@link #REJECT} — fail-fast，限速器返回 {@code ResourceCheck.reject}, launch 抛 BizException
 *   <li>{@link #QUEUE_DEFER} — 限速器返回 {@code ResourceCheck.waitForCapacity}, partition 留 WAITING
 *   <li>{@link #DEGRADE_PRIORITY} — 仍 defer 但 reasonCode 带 {@code _DEGRADED} 后缀, ResourceScheduler
 *       把 决策 priority 降到 1 / band 降到 LOW, fairnessScore 自然落到队尾
 * </ul>
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum QuotaExceededStrategy implements DictEnum {
  REJECT("REJECT", "立即拒绝"),
  QUEUE_DEFER("QUEUE_DEFER", "排队等待"),
  DEGRADE_PRIORITY("DEGRADE_PRIORITY", "降级低优排队");

  private final String code;
  private final String label;

  public static QuotaExceededStrategy from(String value) {
    if (value == null || value.isBlank()) {
      return REJECT;
    }
    QuotaExceededStrategy hit = DictEnum.fromCode(QuotaExceededStrategy.class, value.trim());
    return hit == null ? REJECT : hit;
  }
}
