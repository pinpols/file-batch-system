package io.github.pinpols.batch.console.domain.workflow.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionDetailResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作流图形渲染器: 节点形状、连线箭头与文本转义输出")
class WorkflowMermaidRendererTest {

  @Test
  @DisplayName("起点、任务与终点渲染为对应形状, 成功连线带小写标签")
  void shouldRenderStartTaskEndChain_whenRenderingNodesAndEdges() {
    WorkflowDefinitionDetailResponse detail = detail(
        List.of(
            node("START_0", "起点", "START"), node("LOAD", "加载", "TASK"), node("END_0", "终点", "END")),
        List.of(edge("START_0", "LOAD", "ALWAYS", null), edge("LOAD", "END_0", "SUCCESS", null)));

    String mermaid = WorkflowMermaidRenderer.render(detail);

    assertThat(mermaid)
        .startsWith("flowchart LR\n")
        .contains("START_0([起点 · START_0])")
        .contains("LOAD[加载 · LOAD]")
        .contains("END_0([终点 · END_0])")
        .contains("START_0 --> LOAD")
        .contains("LOAD -- success --> END_0");
  }

  @Test
  @DisplayName("网关渲染为菱形, 条件连线带引号包裹的条件标签")
  void shouldRenderGatewayShapesAndConditionLabels_whenRenderingGateway() {
    WorkflowDefinitionDetailResponse detail = detail(
        List.of(
            node("START_0", null, "START"),
            node("GW", "分流", "GATEWAY"),
            node("A", null, "TASK"),
            node("END_0", null, "END")),
        List.of(
            edge("START_0", "GW", "ALWAYS", null),
            edge("GW", "A", "CONDITION", "x > 0"),
            edge("GW", "END_0", "CONDITION", "x <= 0"),
            edge("A", "END_0", "ALWAYS", null)));

    String mermaid = WorkflowMermaidRenderer.render(detail);

    assertThat(mermaid)
        .contains("GW{分流 · GW}")
        .contains("GW -- \"x > 0\" --> A")
        .contains("GW -- \"x <= 0\" --> END_0");
  }

  @Test
  @DisplayName("文件步骤与等待节点分别渲染为平行四边形与子程序形状")
  void shouldRenderFileStepAndWaitShapes_whenRenderingNodes() {
    WorkflowDefinitionDetailResponse detail = detail(
        List.of(
            node("START_0", null, "START"),
            node("FILE_1", "文件导入", "FILE_STEP"),
            node("WAIT_1", "等到货", "WAIT"),
            node("END_0", null, "END")),
        List.of(
            edge("START_0", "FILE_1", "ALWAYS", null),
            edge("FILE_1", "WAIT_1", "ALWAYS", null),
            edge("WAIT_1", "END_0", "ALWAYS", null)));

    String mermaid = WorkflowMermaidRenderer.render(detail);

    assertThat(mermaid).contains("FILE_1[(文件导入 · FILE_1)]").contains("WAIT_1[[等到货 · WAIT_1]]");
  }

  @Test
  @DisplayName("失败连线渲染为带失败标签的点线箭头")
  void shouldRenderDottedArrow_whenEdgeIsFailure() {
    WorkflowDefinitionDetailResponse detail = detail(
        List.of(
            node("START_0", null, "START"),
            node("T", null, "TASK"),
            node("CATCH", null, "TASK"),
            node("END_0", null, "END")),
        List.of(
            edge("START_0", "T", "ALWAYS", null),
            edge("T", "CATCH", "FAILURE", null),
            edge("T", "END_0", "SUCCESS", null),
            edge("CATCH", "END_0", "ALWAYS", null)));

    String mermaid = WorkflowMermaidRenderer.render(detail);

    assertThat(mermaid).contains("T -. failure .-> CATCH");
  }

  @Test
  @DisplayName("节点编码含非字母数字字符时, 归一化为合法标识且数字开头补前缀")
  void shouldSanitizeNodeCode_whenCodeContainsNonAscii() {
    assertThat(WorkflowMermaidRenderer.sanitizeId("订单-A.1")).matches("^[A-Za-z][A-Za-z0-9_]*$");
    assertThat(WorkflowMermaidRenderer.sanitizeId("123start")).startsWith("n");
  }

  @Test
  @DisplayName("标签转义后不再包含引号与换行符")
  void shouldStripQuotesAndNewlines_whenEscapingLabel() {
    assertThat(WorkflowMermaidRenderer.escapeLabel("a \"quoted\"\nlabel"))
        .doesNotContain("\"")
        .doesNotContain("\n");
  }

  @Test
  @DisplayName("工作流定义为空时只输出流程图声明头")
  void shouldReturnHeaderOnly_whenDetailMissing() {
    assertThat(WorkflowMermaidRenderer.render(null)).isEqualTo("flowchart LR\n");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static WorkflowDefinitionDetailResponse detail(
      List<ConsoleWorkflowNodeResponse> nodes, List<ConsoleWorkflowEdgeResponse> edges) {
    return new WorkflowDefinitionDetailResponse(
        1L, "t1", "WF_DEMO", "Demo", "DAG", 1, true, "demo workflow", null, null, nodes, edges);
  }

  private static ConsoleWorkflowNodeResponse node(String code, String name, String type) {
    return new ConsoleWorkflowNodeResponse(
        1L, 1L, code, name, type, null, null, null, null, null, null, null, null, null, true, null,
        null, null, null);
  }

  private static ConsoleWorkflowEdgeResponse edge(
      String from, String to, String edgeType, String condition) {
    return new ConsoleWorkflowEdgeResponse(1L, 1L, from, to, edgeType, condition, true, null, null);
  }
}
