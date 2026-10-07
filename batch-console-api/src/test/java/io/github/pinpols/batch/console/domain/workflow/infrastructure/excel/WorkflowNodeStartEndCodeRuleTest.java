package io.github.pinpols.batch.console.domain.workflow.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("编排节点起止编码规则: 起止节点唯一性与类型匹配校验")
class WorkflowNodeStartEndCodeRuleTest {

  @Test
  @DisplayName("起止节点编码规范时校验通过, 不产生任何问题项")
  void shouldPass_whenStartAndEndPresentWithCanonicalCodes() {
    List<Map<String, Object>> rows = List.of(
        node("ta", "WF_A", "1", "START", "START", 2),
        node("ta", "WF_A", "1", "NODE_JOB1", "JOB", 3),
        node("ta", "WF_A", "1", "END", "END", 4));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues).isEmpty();
  }

  @Test
  @DisplayName("节点类型为起始而编码不是起始码时报出一个问题, 指向编码列且行号为 2")
  void shouldReport_whenNodeTypeStartButCodeIsNotStart() {
    List<Map<String, Object>> rows = List.of(
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
  @DisplayName("缺少起始节点时报错, 提示起始节点数应为 1 而实际为 0")
  void shouldReport_whenStartNodeMissing() {
    List<Map<String, Object>> rows = List.of(
        node("ta", "WF_C", "1", "NODE_JOB", "JOB", 2), node("ta", "WF_C", "1", "END", "END", 3));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues)
        .anySatisfy(i -> assertThat(i.message()).contains("exactly 1 START node, found 0"));
  }

  @Test
  @DisplayName("同一编排内出现两个结束节点时报错, 提示结束节点数应为 1 而实际为 2")
  void shouldReport_whenTwoEndNodesInSameWorkflow() {
    List<Map<String, Object>> rows = List.of(
        node("ta", "WF_D", "1", "START", "START", 2),
        node("ta", "WF_D", "1", "END", "END", 3),
        node("ta", "WF_D", "1", "END", "END", 4));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues)
        .anySatisfy(i -> assertThat(i.message()).contains("exactly 1 END node, found 2"));
  }

  @Test
  @DisplayName("编码为起始码但节点类型不匹配时报错, 问题指向节点类型列")
  void shouldReport_whenNodeCodeIsStartButNodeTypeMismatched() {
    List<Map<String, Object>> rows = List.of(
        node("ta", "WF_E", "1", "START", "JOB", 2), node("ta", "WF_E", "1", "END", "END", 3));

    List<WorkbookIssue> issues = WorkflowNodeStartEndCodeRule.validate(rows);

    assertThat(issues).anySatisfy(i -> {
      assertThat(i.columnName()).isEqualTo("node_type");
      assertThat(i.message()).contains("node_code='START' must have node_type='START'");
    });
  }

  @Test
  @DisplayName("空行集合与空引用输入均返回空问题列表")
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
