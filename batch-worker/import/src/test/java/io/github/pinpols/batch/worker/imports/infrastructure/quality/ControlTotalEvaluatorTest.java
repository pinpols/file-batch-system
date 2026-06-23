package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-041 Phase1.2:控制金额对账(controlTotalCheck)。 */
class ControlTotalEvaluatorTest {

  private final ControlTotalEvaluator evaluator =
      new ControlTotalEvaluator(new ValidationConfigSupport(new ObjectMapper()));

  private static List<Map<String, Object>> rows(String... amounts) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String amount : amounts) {
      rows.add(Map.of("amount", amount));
    }
    return rows;
  }

  @Test
  @DisplayName("expected 与累加和一致 → 无 issue,记入 appliedChecks")
  void matchingTotal_noIssue() {
    ValidationSession session =
        session(Map.of("controlTotalCheck", Map.of("amountField", "amount", "expected", "300.00")));
    evaluator.accumulate(session, rows("100.00", "200.00"));
    assertThat(evaluator.finalizeCheck(session)).isNull();
    assertThat(session.appliedChecks()).contains("control_total_check");
  }

  @Test
  @DisplayName("blocker=true 且金额不符 → 阻断性 ValidationIssue")
  void blockerMismatch_raisesIssue() {
    ValidationSession session =
        session(
            Map.of(
                "controlTotalCheck",
                Map.of("amountField", "amount", "expected", "999.00", "blocker", true)));
    evaluator.accumulate(session, rows("100.00", "200.00"));
    ValidationIssue issue = evaluator.finalizeCheck(session);
    assertThat(issue).isNotNull();
    assertThat(issue.errorCode()).isEqualTo("IMPORT_VALIDATE_CONTROL_TOTAL");
  }

  @Test
  @DisplayName("默认告警(blocker 缺省):金额不符也不阻断,无 issue")
  void mismatchAlertOnly_noIssue() {
    ValidationSession session =
        session(Map.of("controlTotalCheck", Map.of("amountField", "amount", "expected", "999.00")));
    evaluator.accumulate(session, rows("100.00", "200.00"));
    assertThat(evaluator.finalizeCheck(session)).isNull();
    assertThat(session.appliedChecks()).contains("control_total_check");
  }

  @Test
  @DisplayName("容差内的小数尾差 → 放行")
  void withinTolerance_noIssue() {
    ValidationSession session =
        session(
            Map.of(
                "controlTotalCheck",
                Map.of(
                    "amountField",
                    "amount",
                    "expected",
                    "300.00",
                    "blocker",
                    true,
                    "tolerance",
                    "0.05")));
    evaluator.accumulate(session, rows("100.00", "200.02"));
    assertThat(evaluator.finalizeCheck(session)).isNull();
  }

  @Test
  @DisplayName("无 expected 时回落 trailer 声明的 declaredControlTotal")
  void declaredFromTrailerAttr() {
    ValidationSession session =
        session(Map.of("controlTotalCheck", Map.of("amountField", "amount", "blocker", true)));
    session
        .context()
        .getAttributes()
        .put(ControlTotalEvaluator.ATTR_DECLARED_CONTROL_TOTAL, new BigDecimal("300.00"));
    evaluator.accumulate(session, rows("100.00", "200.00"));
    assertThat(evaluator.finalizeCheck(session)).isNull();
    assertThat(session.appliedChecks()).contains("control_total_check");
  }

  @Test
  @DisplayName("未配 controlTotalCheck → accumulate/finalize 全 no-op,不记 appliedChecks")
  void noRule_isNoOp() {
    ValidationSession session = session(Map.of());
    evaluator.accumulate(session, rows("100.00"));
    assertThat(evaluator.finalizeCheck(session)).isNull();
    assertThat(session.appliedChecks()).doesNotContain("control_total_check");
  }

  private ValidationSession session(Map<String, Object> ruleSet) {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.setFileId("f1");
    context.setAttributes(new LinkedHashMap<>());
    return new ValidationSession(
        context,
        ruleSet,
        0L,
        null,
        null,
        List.of(),
        new LinkedHashMap<>(),
        new ArrayList<>(),
        new LinkedHashMap<>(),
        new LinkedHashSet<>());
  }
}
