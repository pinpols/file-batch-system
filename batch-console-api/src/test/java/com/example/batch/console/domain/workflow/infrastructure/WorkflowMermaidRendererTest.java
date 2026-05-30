package com.example.batch.console.domain.workflow.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowMermaidRendererTest {

  @Test
  void rendersStartTaskEndChainWithCorrectShapes() {
    WorkflowDefinitionDetailResponse detail =
        detail(
            List.of(
                node("START_0", "起点", "START"),
                node("LOAD", "加载", "TASK"),
                node("END_0", "终点", "END")),
            List.of(
                edge("START_0", "LOAD", "ALWAYS", null), edge("LOAD", "END_0", "SUCCESS", null)));

    String mermaid = WorkflowMermaidRenderer.render(detail);

    assertThat(mermaid).startsWith("flowchart LR\n");
    assertThat(mermaid).contains("START_0([起点 · START_0])");
    assertThat(mermaid).contains("LOAD[加载 · LOAD]");
    assertThat(mermaid).contains("END_0([终点 · END_0])");
    assertThat(mermaid).contains("START_0 --> LOAD");
    assertThat(mermaid).contains("LOAD -- success --> END_0");
  }

  @Test
  void rendersGatewayWithConditionEdgeLabel() {
    WorkflowDefinitionDetailResponse detail =
        detail(
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

    assertThat(mermaid).contains("GW{分流 · GW}");
    assertThat(mermaid).contains("GW -- \"x > 0\" --> A");
    assertThat(mermaid).contains("GW -- \"x <= 0\" --> END_0");
  }

  @Test
  void shapesForFileStepAndWaitNodes() {
    WorkflowDefinitionDetailResponse detail =
        detail(
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

    assertThat(mermaid).contains("FILE_1[(文件导入 · FILE_1)]");
    assertThat(mermaid).contains("WAIT_1[[等到货 · WAIT_1]]");
  }

  @Test
  void failureEdgeUsesDottedArrow() {
    WorkflowDefinitionDetailResponse detail =
        detail(
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
  void sanitizesNonAsciiNodeCodeIntoValidMermaidId() {
    assertThat(WorkflowMermaidRenderer.sanitizeId("订单-A.1")).matches("^[A-Za-z][A-Za-z0-9_]*$");
    assertThat(WorkflowMermaidRenderer.sanitizeId("123start")).startsWith("n");
  }

  @Test
  void escapeLabelStripsQuotesAndNewlines() {
    assertThat(WorkflowMermaidRenderer.escapeLabel("a \"quoted\"\nlabel")).doesNotContain("\"");
    assertThat(WorkflowMermaidRenderer.escapeLabel("a \"quoted\"\nlabel")).doesNotContain("\n");
  }

  @Test
  void nullDetailReturnsHeaderOnly() {
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
