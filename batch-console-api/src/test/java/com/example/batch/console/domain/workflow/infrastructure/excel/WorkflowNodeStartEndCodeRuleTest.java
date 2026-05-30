package com.example.batch.console.domain.workflow.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowNodeStartEndCodeRuleTest {

  @Test
  void shouldPass_whenStartAndEndPresentWithCanonicalCodes() {
    List<Map<String, Object>> rows =
        List.of(
            node("ta", "WF_A", "1", "START", "START", 2),
            node("ta", "WF_A", "1", "NODE_JOB1", "JOB", 3),
            node("ta", "WF_A", "1", "END", "END", 4));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues).isEmpty();
  }

  @Test
  void shouldReport_whenNodeTypeStartButCodeIsNotStart() {
    List<Map<String, Object>> rows =
        List.of(
            node("ta", "WF_B", "1", "NODE_START", "START", 2),
            node("ta", "WF_B", "1", "END", "END", 4));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).columnName()).isEqualTo("node_code");
    assertThat(issues.get(0).message())
        .contains("node_type=START must use node_code='START'")
        .contains("NODE_START");
    assertThat(issues.get(0).rowNo()).isEqualTo(2);
  }

  @Test
  void shouldReport_whenStartNodeMissing() {
    List<Map<String, Object>> rows =
        List.of(
            node("ta", "WF_C", "1", "NODE_JOB", "JOB", 2),
            node("ta", "WF_C", "1", "END", "END", 3));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues)
        .anySatisfy(i -> assertThat(i.message()).contains("exactly 1 START node, found 0"));
  }

  @Test
  void shouldReport_whenTwoEndNodesInSameWorkflow() {
    List<Map<String, Object>> rows =
        List.of(
            node("ta", "WF_D", "1", "START", "START", 2),
            node("ta", "WF_D", "1", "END", "END", 3),
            node("ta", "WF_D", "1", "END", "END", 4));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues)
        .anySatisfy(i -> assertThat(i.message()).contains("exactly 1 END node, found 2"));
  }

  @Test
  void shouldReport_whenNodeCodeIsStartButNodeTypeMismatched() {
    List<Map<String, Object>> rows =
        List.of(
            node("ta", "WF_E", "1", "START", "JOB", 2), node("ta", "WF_E", "1", "END", "END", 3));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues)
        .anySatisfy(
            i -> {
              assertThat(i.columnName()).isEqualTo("node_type");
              assertThat(i.message()).contains("node_code='START' must have node_type='START'");
            });
  }

  @Test
  void shouldHandleEmptyRows() {
    assertThat(WorkflowNodeStartEndCodeRule.validate(List.of())).isEmpty();
    assertThat(WorkflowNodeStartEndCodeRule.validate(null)).isEmpty();
  }

  private static Map<String, Object> node(
      String tenant, String wf, String ver, String code, String type, int rowNo) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(ConfigPackageExcelValidator.COL_TENANT_ID, tenant);
    m.put(ConfigPackageExcelValidator.COL_WORKFLOW_CODE, wf);
    m.put(ConfigPackageExcelValidator.COL_WORKFLOW_VERSION, ver);
    m.put(ConfigPackageExcelValidator.COL_NODE_CODE, code);
    m.put(ConfigPackageExcelValidator.COL_NODE_TYPE, type);
    m.put("__excel_row_no", rowNo);
    return m;
  }
}
