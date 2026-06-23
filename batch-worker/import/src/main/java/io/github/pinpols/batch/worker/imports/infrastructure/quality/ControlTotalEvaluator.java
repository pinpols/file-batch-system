package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.booleanValue;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.decimalValue;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.enabled;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.firstNonNull;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringValue;

import io.github.pinpols.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ADR-041 Phase1.2:控制金额对账。跨 chunk 累加 amountField 金额,所有记录处理完后与声明总额(规则 expected 或 P1.1 trailer 声明的
 * declaredControlTotal)比对。默认告警,blocker=true 才阻断。控制总额只验「数对不对」(传输完整性), 不裁定业务对错(ADR-021 边界)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlTotalEvaluator {

  static final String ATTR_RUNNING_SUM = "controlTotalRunningSum";

  // 与 P1.1 TrailerControlRecord.ATTR_DECLARED_CONTROL_TOTAL 同值;此处用字面量以保持 P1.2 不依赖 P1.1 类,
  // 两 PR 可独立合并。P1.1 在 PARSE 阶段剥离 trailer 后把声明总额写进该 attribute。
  static final String ATTR_DECLARED_CONTROL_TOTAL = "declaredControlTotal";

  private final ValidationConfigSupport configSupport;

  /** 每个 chunk 调用:按 amountField 累加金额到 running sum(仅 controlTotalCheck 启用且配了 amountField 时)。 */
  public void accumulate(ValidationSession session, List<Map<String, Object>> rows) {
    if (session == null || session.context() == null || rows == null || rows.isEmpty()) {
      return;
    }
    Map<String, Object> rule = rule(session);
    if (rule.isEmpty() || !enabled(rule)) {
      return;
    }
    String amountField = amountField(rule);
    if (!Texts.hasText(amountField)) {
      return;
    }
    BigDecimal sum = runningSum(session);
    for (Map<String, Object> row : rows) {
      BigDecimal value = decimalValue(row.get(amountField));
      if (value != null) {
        sum = sum.add(value);
      }
    }
    session.context().getAttributes().put(ATTR_RUNNING_SUM, sum);
  }

  /**
   * 所有 chunk 结束后调用:running sum vs 声明总额。容差内 → null;超差 + blocker → ValidationIssue;超差 + !blocker → 仅
   * warn 返回 null。声明总额取自规则 expected,缺省回落 P1.1 trailer 声明值。
   */
  public ValidationIssue finalizeCheck(ValidationSession session) {
    if (session == null || session.context() == null) {
      return null;
    }
    Map<String, Object> rule = rule(session);
    if (rule.isEmpty() || !enabled(rule)) {
      return null;
    }
    String amountField = amountField(rule);
    if (!Texts.hasText(amountField)) {
      return null;
    }
    BigDecimal declared = declared(session, rule);
    if (declared == null) {
      log.warn(
          "control-total check enabled but no declared total (expected/trailer): tenantId={},"
              + " fileId={}",
          session.context().getTenantId(),
          session.context().getFileId());
      return null;
    }
    session.appliedChecks().add("control_total_check");
    BigDecimal actual = runningSum(session);
    BigDecimal tolerance = decimalValue(rule.get("tolerance"));
    if (tolerance == null) {
      tolerance = BigDecimal.ZERO;
    }
    if (actual.subtract(declared).abs().compareTo(tolerance) <= 0) {
      return null;
    }
    if (booleanValue(rule.get("blocker"), false)) {
      return new ValidationIssue(
          null,
          "IMPORT_VALIDATE_CONTROL_TOTAL",
          "control-total mismatch, declared="
              + declared.toPlainString()
              + ", actual="
              + actual.toPlainString(),
          Map.of("declared", declared, "actual", actual, "field", amountField));
    }
    log.warn(
        "control-total mismatch (alert-only): declared={}, actual={}, field={}, tenantId={},"
            + " fileId={}",
        declared.toPlainString(),
        actual.toPlainString(),
        amountField,
        session.context().getTenantId(),
        session.context().getFileId());
    return null;
  }

  private Map<String, Object> rule(ValidationSession session) {
    return configSupport.firstMap(session.ruleSet(), "controlTotalCheck", "control_total_check");
  }

  private String amountField(Map<String, Object> rule) {
    return stringValue(firstNonNull(rule.get("amountField"), rule.get("amount_field")));
  }

  private BigDecimal declared(ValidationSession session, Map<String, Object> rule) {
    BigDecimal explicit =
        decimalValue(firstNonNull(rule.get("expected"), rule.get("expectedTotal")));
    if (explicit != null) {
      return explicit;
    }
    return decimalValue(session.context().getAttributes().get(ATTR_DECLARED_CONTROL_TOTAL));
  }

  private BigDecimal runningSum(ValidationSession session) {
    BigDecimal value = decimalValue(session.context().getAttributes().get(ATTR_RUNNING_SUM));
    return value == null ? BigDecimal.ZERO : value;
  }
}
