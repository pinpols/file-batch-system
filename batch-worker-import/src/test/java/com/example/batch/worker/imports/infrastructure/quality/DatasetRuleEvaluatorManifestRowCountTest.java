package com.example.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-040:sidecar(.chk manifest)声明的 expectedRecordCount 与实际累加行数对账。 */
class DatasetRuleEvaluatorManifestRowCountTest {

  private final DatasetRuleEvaluator evaluator =
      new DatasetRuleEvaluator(new ValidationConfigSupport(new ObjectMapper()));

  @Test
  @DisplayName("manifest 声明行数与实际不符(模板无 rowCountCheck)→ 产生 issue 且标 source=manifest")
  void manifestMismatch_raisesIssue() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(session(Map.of(), 1000, 999L, issues, applied));
    assertThat(applied).contains("row_count_check");
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).errorCode()).isEqualTo("IMPORT_VALIDATE_ROW_COUNT");
    assertThat(issues.get(0).errorMessage()).contains("(manifest)");
    assertThat(issues.get(0).rawRecord()).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> raw = (Map<String, Object>) issues.get(0).rawRecord();
    assertThat(raw).containsEntry("source", "manifest");
  }

  @Test
  @DisplayName("manifest 声明行数与实际一致 → 无 issue,但记入 appliedChecks")
  void manifestMatch_noIssue() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(session(Map.of(), 1000, 1000L, issues, applied));
    assertThat(issues).isEmpty();
    assertThat(applied).contains("row_count_check");
  }

  @Test
  @DisplayName("无 manifest 声明且模板无 rowCountCheck → 不参与(不入 appliedChecks)")
  void noManifestNoRule_skips() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    evaluator.evaluate(session(Map.of(), null, 1000L, issues, applied));
    assertThat(issues).isEmpty();
    assertThat(applied).doesNotContain("row_count_check");
  }

  @Test
  @DisplayName("模板已显式 pin exact → 模板优先,不被 manifest 覆盖(template wins)")
  void templateExactWins_overManifest() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<String> applied = new LinkedHashSet<>();
    // 模板 exact=1000(与实际一致)→ 不报;manifest 声明 500(若被采用会误报)→ 验证未被采用
    evaluator.evaluate(
        session(
            Map.of("rowCountCheck", Map.of("enabled", true, "exact", 1000)),
            500,
            1000L,
            issues,
            applied));
    assertThat(issues).isEmpty();
    assertThat(applied).contains("row_count_check");
  }

  private ValidationSession session(
      Map<String, Object> ruleSet,
      Integer manifestExpected,
      long totalCount,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.setFileId("f1");
    Map<String, Object> attrs = new LinkedHashMap<>();
    if (manifestExpected != null) {
      attrs.put(DatasetRuleEvaluator.ATTR_EXPECTED_RECORD_COUNT, manifestExpected);
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
