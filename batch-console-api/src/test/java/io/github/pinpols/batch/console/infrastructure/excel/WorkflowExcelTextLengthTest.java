package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置包 Workflow 节点与连线文本长度")
class WorkflowExcelTextLengthTest {
  @Test
  @DisplayName("节点名称按 Unicode 码点限制 256 字符")
  void shouldValidateNodeNameLength() {
    assertThat(nodeIssues("😀".repeat(256))).isEmpty();
    assertThat(nodeIssues("😀".repeat(257))).anyMatch(issue -> issue.contains("node_name"));
  }

  @Test
  @DisplayName("边条件表达式按 Unicode 码点限制 1024 字符")
  void shouldValidateConditionLength() {
    assertThat(edgeIssues("😀".repeat(1024))).isEmpty();
    assertThat(edgeIssues("😀".repeat(1025))).anyMatch(issue -> issue.contains("condition_expr"));
  }

  private static List<String> nodeIssues(String name) {
    Map<String, String> row = Map.of(
        "workflow_code",
        "WF_A",
        "workflow_version",
        "1",
        "node_code",
        "START",
        "node_name",
        name,
        "node_type",
        "START");
    List<String> issues = new ArrayList<>();
    ConfigPackageExcelRowValidators.validateWfNodeRow(
        row, Set.of("WF_A:1"), new HashSet<>(), issues);
    return issues;
  }

  private static List<String> edgeIssues(String condition) {
    Map<String, String> row = Map.of(
        "workflow_code",
        "WF_A",
        "workflow_version",
        "1",
        "from_node_code",
        "START",
        "to_node_code",
        "END",
        "edge_type",
        "CONDITION",
        "condition_expr",
        condition);
    List<String> issues = new ArrayList<>();
    ConfigPackageExcelRowValidators.validateWfEdgeRow(
        row, Set.of("WF_A:1"), Set.of("WF_A:1#START", "WF_A:1#END"), issues);
    return issues;
  }
}
