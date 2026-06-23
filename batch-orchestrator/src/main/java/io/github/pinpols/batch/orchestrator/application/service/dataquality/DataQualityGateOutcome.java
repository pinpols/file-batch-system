package io.github.pinpols.batch.orchestrator.application.service.dataquality;

import java.util.List;
import lombok.Builder;

/**
 * ADR-021 DQ gate 执行结果。
 *
 * <ul>
 *   <li>{@link #status} = PASS（无 BLOCKER 失败）/ WARN（仅有 WARN/INFO 失败）/ BLOCKED（任一 BLOCKER 失败）；
 *   <li>{@link #findings} 每条规则的具体结果。
 * </ul>
 *
 * <p>{@code BLOCKED} 时 ResultVersionWriter 强制
 * promotion_policy=MANUAL_APPROVAL，instance.failure_class 落 DATA_QUALITY，等运维显式 promote / reject。
 */
@Builder
public record DataQualityGateOutcome(GateStatus status, List<RuleFinding> findings) {

  public enum GateStatus {
    /** 全部规则通过；EFFECTIVE 链按 ResultPolicy 正常推进。 */
    PASS,
    /** 仅 WARN/INFO 失败；EFFECTIVE 链正常但记 warning。 */
    WARN,
    /** ≥1 条 BLOCKER 失败；EFFECTIVE 链强制 MANUAL_APPROVAL。 */
    BLOCKED,
    /** 无规则关联；DQ gate 跳过。 */
    NO_RULES
  }

  /** 单条规则的执行结果。 */
  @Builder
  public record RuleFinding(
      String ruleCode, String ruleType, String severity, String status, String message) {}

  public static DataQualityGateOutcome noRules() {
    return DataQualityGateOutcome.builder().status(GateStatus.NO_RULES).findings(List.of()).build();
  }
}
