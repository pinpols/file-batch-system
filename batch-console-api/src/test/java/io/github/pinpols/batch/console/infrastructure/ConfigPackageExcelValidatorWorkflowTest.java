package io.github.pinpols.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖 ADR-025 DAG 拓扑静态校验:V1 环 / V2 不可达 / V3 不可终止 / V4 / V18 DSL 引用 / V11 START&END / V17 CONDITION
 * expression。规则实现见 {@code ConfigPackageExcelValidator.validateWorkflowGraphTopology}。
 */
@DisplayName("编排配置包静态拓扑校验: 起止、环、可达性与引用约束")
class ConfigPackageExcelValidatorWorkflowTest {

  private static final String WF_CODE = "WF_DEMO";
  private static final String WF_VERSION = "1";

  @Test
  @DisplayName("起止节点齐备的链式编排图不产生编排表相关问题")
  void shouldReportNoTopologyIssues_whenChainWorkflowIsValid() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(node("START_0", "START"), node("TASK_1", "TASK"), node("END_0", "END")),
        List.of(edge("START_0", "TASK_1", "ALWAYS"), edge("TASK_1", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .filteredOn(i -> i.sheetName().startsWith("workflow_"))
        .isEmpty();
  }

  @Test
  @DisplayName("缺少起始节点时报错并提示缺少起始节点")
  void shouldFail_whenStartNodeMissing() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(node("TASK_1", "TASK"), node("END_0", "END")),
        List.of(edge("TASK_1", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message()).contains("missing START node"));
  }

  @Test
  @DisplayName("存在多个起始节点时报错并提示起始节点重复")
  void shouldFail_whenMultipleStartNodes() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(
            node("START_A", "START"),
            node("START_B", "START"),
            node("TASK_1", "TASK"),
            node("END_0", "END")),
        List.of(
            edge("START_A", "TASK_1", "ALWAYS"),
            edge("START_B", "TASK_1", "ALWAYS"),
            edge("TASK_1", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message()).contains("multiple START nodes"));
  }

  @Test
  @DisplayName("缺少结束节点时报错并提示至少需要一个结束节点")
  void shouldFail_whenEndNodeMissing() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(node("START_0", "START"), node("TASK_1", "TASK")),
        List.of(edge("START_0", "TASK_1", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message()).contains("at least 1 END node"));
  }

  @Test
  @DisplayName("编排图中存在环时报错并提示拓扑成环")
  void shouldFail_whenGraphHasCycle() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(
            node("START_0", "START"),
            node("A", "TASK"),
            node("B", "TASK"),
            node("C", "TASK"),
            node("END_0", "END")),
        List.of(
            edge("START_0", "A", "ALWAYS"),
            edge("A", "B", "ALWAYS"),
            edge("B", "C", "ALWAYS"),
            edge("C", "A", "ALWAYS"), // cycle
            edge("C", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message()).contains("topology cycle"));
  }

  @Test
  @DisplayName("节点存在指向自身的边时报错并提示自环")
  void shouldFail_whenNodeHasSelfLoop() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(node("START_0", "START"), node("A", "TASK"), node("END_0", "END")),
        List.of(
            edge("START_0", "A", "ALWAYS"),
            edge("A", "A", "ALWAYS"), // self-loop
            edge("A", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message()).contains("self-loop"));
  }

  @Test
  @DisplayName("存在从起始节点不可达的节点时报错并点名该节点")
  void shouldFail_whenNodeUnreachableFromStart() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(
            node("START_0", "START"),
            node("A", "TASK"),
            node("ORPHAN", "TASK"),
            node("END_0", "END")),
        List.of(edge("START_0", "A", "ALWAYS"), edge("A", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(
            i -> assertThat(i.message()).contains("unreachable from START").contains("ORPHAN"));
  }

  @Test
  @DisplayName("存在无法到达任一结束节点的节点时报错并点名该节点")
  void shouldFail_whenNodeCannotReachAnyEnd() {
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(
            node("START_0", "START"),
            node("A", "TASK"),
            node("DEADEND", "TASK"),
            node("END_0", "END")),
        List.of(
            edge("START_0", "A", "ALWAYS"),
            edge("START_0", "DEADEND", "ALWAYS"),
            edge("A", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(
            i -> assertThat(i.message()).contains("cannot reach any END").contains("DEADEND"));
  }

  @Test
  @DisplayName("条件边的条件表达式为空时报错并提示表达式必须非空")
  void shouldFail_whenConditionEdgeExpressionBlank() {
    Map<String, String> condEdge = edge("GW", "A", "CONDITION");
    condEdge.put("condition_expr", ""); // 显式空
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(
            node("START_0", "START"),
            node("GW", "GATEWAY"),
            node("A", "TASK"),
            node("END_0", "END")),
        List.of(
            edge("START_0", "GW", "ALWAYS"),
            condEdge,
            edge("GW", "END_0", "ALWAYS"),
            edge("A", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message())
            .contains("CONDITION edge")
            .contains("non-empty condition_expr"));
  }

  @Test
  @DisplayName("节点参数引用不存在的节点时报错并点名该引用")
  void shouldFail_whenDslReferencesMissingNode() {
    Map<String, String> n = node("DOWN", "TASK");
    n.put("node_params", "{\"src\":\"$.nodes.MISSING_NODE.output.fileId\"}");
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(node("START_0", "START"), n, node("END_0", "END")),
        List.of(edge("START_0", "DOWN", "ALWAYS"), edge("DOWN", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message())
            .contains("DSL references missing node")
            .contains("MISSING_NODE"));
  }

  @Test
  @DisplayName("节点引用非上游分支节点的输出时报错并提示只能引用上游节点")
  void shouldFail_whenDslReferencesNonAncestorNode() {
    // A 和 B 是 START 的两个独立分支,A 试图引用 B 的输出 → B 不是 A 的祖先 → 拒绝
    Map<String, String> a = node("A", "TASK");
    a.put("node_params", "{\"src\":\"$.nodes.B.output.fileId\"}");
    PackageExcelSession session = wfSession(
        List.of(wfDef()),
        List.of(node("START_0", "START"), a, node("B", "TASK"), node("END_0", "END")),
        List.of(
            edge("START_0", "A", "ALWAYS"),
            edge("START_0", "B", "ALWAYS"),
            edge("A", "END_0", "ALWAYS"),
            edge("B", "END_0", "ALWAYS")));

    var result = validator().validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(i -> assertThat(i.message())
            .contains("can only reference upstream nodes")
            .contains("'B'"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static ConfigPackageExcelValidator validator() {
    return new ConfigPackageExcelValidator(
        mock(JobDefinitionMapper.class),
        mock(PipelineDefinitionMapper.class),
        mock(StepRegistryQueryMapper.class),
        mock(FileTemplateConfigMapper.class),
        mock(ResourceQueueMapper.class),
        mock(BusinessCalendarMapper.class),
        mock(BatchWindowMapper.class));
  }

  private static PackageExcelSession wfSession(
      List<Map<String, String>> wfDefs,
      List<Map<String, String>> wfNodes,
      List<Map<String, String>> wfEdges) {
    return new PackageExcelSession(
        "wf.xlsx",
        "t1",
        Instant.EPOCH,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        wfDefs,
        wfNodes,
        wfEdges);
  }

  private static Map<String, String> wfDef() {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("workflow_code", WF_CODE);
    row.put("workflow_name", "demo");
    row.put("workflow_type", "DAG");
    row.put("version", WF_VERSION);
    row.put("enabled", "true");
    return row;
  }

  private static Map<String, String> node(String nodeCode, String nodeType) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("workflow_code", WF_CODE);
    row.put("workflow_version", WF_VERSION);
    row.put("node_code", nodeCode);
    row.put("node_name", nodeCode);
    row.put("node_type", nodeType);
    row.put("enabled", "true");
    return row;
  }

  private static Map<String, String> edge(String from, String to, String edgeType) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("workflow_code", WF_CODE);
    row.put("workflow_version", WF_VERSION);
    row.put("from_node_code", from);
    row.put("to_node_code", to);
    row.put("edge_type", edgeType);
    row.put("enabled", "true");
    return row;
  }
}
