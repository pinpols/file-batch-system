package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-041 Phase1.1:trailer 声明笔数 vs 实际解析记录数对账(controlRecordCheck)。 */
class DatasetRuleEvaluatorControlRecordTest {

  private final DatasetRuleEvaluator evaluator =
      new DatasetRuleEvaluator(new ValidationConfigSupport(new ObjectMapper()));

  @Test
  @DisplayName("blocker=true 且声明笔数与实际不符 → 产生阻断性 ValidationIssue")
  void blockerMismatch_raisesIssue() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(
        session(
            Map.of("controlRecordCheck", Map.of("enabled", true, "blocker", true)),
            10L,
            1000L,
            issues,
            applied));
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).errorCode()).isEqualTo("IMPORT_VALIDATE_CONTROL_RECORD");
    assertThat(applied).contains("control_record_check");
  }

  @Test
  @DisplayName("声明笔数与实际一致 → 无 issue,但记入 appliedChecks")
  void matchingCount_noIssue() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(
        session(
            Map.of("controlRecordCheck", Map.of("enabled", true, "blocker", true)),
            1000L,
            1000L,
            issues,
            applied));
    assertThat(issues).isEmpty();
    assertThat(applied).contains("control_record_check");
  }

  @Test
  @DisplayName("默认告警(blocker 缺省=false):不符也不阻断,无 issue")
  void mismatchAlertOnly_noIssue() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(
        session(
            Map.of("controlRecordCheck", Map.of("enabled", true)), 10L, 1000L, issues, applied));
    assertThat(issues).isEmpty();
    assertThat(applied).contains("control_record_check");
  }

  @Test
  @DisplayName("trailer 未带出声明笔数 → 不参与(不入 appliedChecks,不抛)")
  void noDeclaredCount_skips() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(
        session(
            Map.of("controlRecordCheck", Map.of("enabled", true, "blocker", true)),
            null,
            1000L,
            issues,
            applied));
    assertThat(issues).isEmpty();
    assertThat(applied).doesNotContain("control_record_check");
  }

  private ValidationSession session(
      Map<String, Object> ruleSet,
      Long declaredCount,
      long totalCount,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.setFileId("f1");
    Map<String, Object> attrs = new LinkedHashMap<>();
    if (declaredCount != null) {
      attrs.put(TrailerControlRecord.ATTR_DECLARED_RECORD_COUNT, declaredCount);
    }
    context.setAttributes(attrs);
    return new ValidationSession(
        context,
        ruleSet,
        totalCount,
        null,
        null,
        List.of(),
        new LinkedHashMap<>(),
        datasetIssues,
        new LinkedHashMap<>(),
        appliedChecks);
  }
}
