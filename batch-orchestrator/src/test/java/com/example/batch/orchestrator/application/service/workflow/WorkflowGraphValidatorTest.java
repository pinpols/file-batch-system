package com.example.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowGraphValidatorTest {

  private WorkflowNodeMapper nodeMapper;
  private WorkflowEdgeMapper edgeMapper;
  private WorkflowGraphValidator validator;

  @BeforeEach
  void setUp() {
    nodeMapper = mock(WorkflowNodeMapper.class);
    edgeMapper = mock(WorkflowEdgeMapper.class);
    validator = new WorkflowGraphValidator(nodeMapper, edgeMapper);
  }

  @Test
  void linearStartTaskEndIsClean() {
    seed(nodes("START", "TASK1", "END"), edges(edge("START", "TASK1"), edge("TASK1", "END")));
    var result = validator.validate(1L);
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void selfLoopReportsV1() {
    seed(nodes("START", "TASK1", "END"), edges(edge("START", "TASK1"), edge("TASK1", "TASK1")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V1"));
  }

  @Test
  void cycleReportsV1() {
    seed(
        nodes("START", "A", "B", "END"),
        edges(edge("START", "A"), edge("A", "B"), edge("B", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V1"));
  }

  @Test
  void unreachableNodeReportsV2() {
    seed(nodes("START", "A", "ORPHAN", "END"), edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors())
        .anySatisfy(
            i -> {
              assertThat(i.code()).isEqualTo("V2");
              assertThat(i.nodeCode()).isEqualTo("ORPHAN");
            });
  }

  @Test
  void deadEndNodeReportsV3() {
    seed(
        nodes("START", "A", "DEAD", "END"),
        edges(edge("START", "A"), edge("A", "DEAD"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V3"));
  }

  @Test
  void dslReferencesMissingNodeReportsV4() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setNodeParams("{\"file\":\"$.nodes.MISSING.output.fileId\"}");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V4"));
  }

  @Test
  void invalidCrossDayDepJsonReportsV6() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setCrossDayDependencies("not-json{[");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V6"));
  }

  @Test
  void crossDayDepRangeOver90DaysReportsV7() {
    var nodes = nodes("START", "A", "END");
    nodes
        .get(1)
        .setCrossDayDependencies(
            "[{\"jobCode\":\"X\",\"bizDateRange\":\"PREV_120_BIZ_DAYS\",\"scope\":\"REQUIRED\"}]");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V7"));
  }

  @Test
  void gatewayAllOfWithSingleIncomingReportsV9() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setNodeType("GATEWAY");
    nodes.get(1).setNodeParams("{\"joinMode\":\"ALL_OF\"}");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V9"));
  }

  @Test
  void gatewayNOfMInconsistentReportsV10() {
    var nodes = nodes("START", "B", "C", "GW", "END");
    nodes.get(3).setNodeType("GATEWAY");
    nodes.get(3).setNodeParams("{\"joinMode\":\"3_OF_4\"}");
    seed(
        nodes,
        edges(
            edge("START", "B"),
            edge("START", "C"),
            edge("B", "GW"),
            edge("C", "GW"),
            edge("GW", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V10"));
  }

  @Test
  void startWithIncomingReportsV11() {
    seed(
        nodes("START", "A", "END"),
        edges(edge("A", "START"), edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V11"));
  }

  @Test
  void duplicateNodeCodeReportsV13() {
    var nodes = new ArrayList<WorkflowNodeEntity>();
    nodes.add(node("START", "START"));
    nodes.add(node("A", "TASK"));
    nodes.add(node("A", "TASK")); // dup
    nodes.add(node("END", "END"));
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V13"));
  }

  @Test
  void edgeRefsMissingNodeReportsV14() {
    seed(
        nodes("START", "A", "END"),
        edges(edge("START", "A"), edge("A", "GHOST"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V14"));
  }

  @Test
  void emptyWorkflowIsClean() {
    when(nodeMapper.selectByWorkflowDefinitionId(eq(1L))).thenReturn(List.of());
    when(edgeMapper.selectAllByWorkflowDefinitionId(eq(1L))).thenReturn(List.of());
    var result = validator.validate(1L);
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void nullWorkflowIdReturnsClean() {
    var result = validator.validate(null);
    assertThat(result.hasErrors()).isFalse();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void seed(List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
    when(nodeMapper.selectByWorkflowDefinitionId(eq(1L))).thenReturn(nodes);
    when(edgeMapper.selectAllByWorkflowDefinitionId(eq(1L))).thenReturn(edges);
  }

  private static List<WorkflowNodeEntity> nodes(String... codes) {
    List<WorkflowNodeEntity> list = new ArrayList<>();
    for (String code : codes) {
      String type = "START".equals(code) ? "START" : "END".equals(code) ? "END" : "TASK";
      list.add(node(code, type));
    }
    return list;
  }

  private static WorkflowNodeEntity node(String code, String type) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setNodeCode(code);
    n.setNodeType(type);
    return n;
  }

  private static List<WorkflowEdgeEntity> edges(WorkflowEdgeEntity... e) {
    return new ArrayList<>(List.of(e));
  }

  private static WorkflowEdgeEntity edge(String from, String to) {
    WorkflowEdgeEntity e = new WorkflowEdgeEntity();
    e.setFromNodeCode(from);
    e.setToNodeCode(to);
    e.setEnabled(true);
    return e;
  }
}
