package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkflowDagServiceTest {

  private WorkflowEdgeMapper edgeMapper;
  private WorkflowNodeMapper nodeMapper;
  private WorkflowNodeRunMapper nodeRunMapper;
  private WorkflowConditionEvaluator conditionEvaluator;
  private DefaultWorkflowDagService dagService;

  @BeforeEach
  void setUp() {
    edgeMapper = mock(WorkflowEdgeMapper.class);
    nodeMapper = mock(WorkflowNodeMapper.class);
    nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    conditionEvaluator = mock(WorkflowConditionEvaluator.class);
    dagService =
        new DefaultWorkflowDagService(edgeMapper, nodeMapper, nodeRunMapper, conditionEvaluator);
  }

  // ── resolveNextNodes ──────────────────────────────────────────────────────

  @Test
  void shouldReturnEmptyWhenDefinitionIdIsNull() {
    assertThat(dagService.resolveNextNodes(null, "START", true, null)).isEmpty();
  }

  @Test
  void shouldReturnEmptyWhenCurrentNodeCodeIsBlank() {
    assertThat(dagService.resolveNextNodes(1L, "  ", true, null)).isEmpty();
  }

  @Test
  void shouldReturnEmptyWhenNoOutgoingEdges() {
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of());

    assertThat(dagService.resolveNextNodes(1L, "NODE_A", true, null)).isEmpty();
  }

  @Test
  void shouldFollowAlwaysEdgeRegardlessOfSuccess() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_B", "ALWAYS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_B"))
        .thenReturn(node("NODE_B", WorkflowNodeType.TASK.code()));

    List<WorkflowDagService.DagNodeResolution> result =
        dagService.resolveNextNodes(1L, "NODE_A", false, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).nodeCode()).isEqualTo("NODE_B");
  }

  @Test
  void shouldFollowSuccessEdgeOnlyWhenSuccessTrue() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_B", "SUCCESS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(any(), anyString()))
        .thenReturn(node("NODE_B", WorkflowNodeType.TASK.code()));

    assertThat(dagService.resolveNextNodes(1L, "NODE_A", true, null)).hasSize(1);
    assertThat(dagService.resolveNextNodes(1L, "NODE_A", false, null)).isEmpty();
  }

  @Test
  void shouldFollowFailureEdgeOnlyWhenSuccessFalse() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_ERROR", "FAILURE", null);
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(any(), anyString()))
        .thenReturn(node("NODE_ERROR", WorkflowNodeType.TASK.code()));

    assertThat(dagService.resolveNextNodes(1L, "NODE_A", false, null)).hasSize(1);
    assertThat(dagService.resolveNextNodes(1L, "NODE_A", true, null)).isEmpty();
  }

  @Test
  void shouldFollowConditionEdgeWhenSuccessAndConditionMet() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_B", "CONDITION", "amount > 100");
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));
    when(conditionEvaluator.matches(eq("amount > 100"), anyString())).thenReturn(true);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(any(), anyString()))
        .thenReturn(node("NODE_B", WorkflowNodeType.TASK.code()));

    assertThat(dagService.resolveNextNodes(1L, "NODE_A", true, "{\"amount\":200}")).hasSize(1);
  }

  @Test
  void shouldSkipConditionEdgeWhenConditionNotMet() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_B", "CONDITION", "amount > 100");
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));
    when(conditionEvaluator.matches(anyString(), anyString())).thenReturn(false);

    assertThat(dagService.resolveNextNodes(1L, "NODE_A", true, "{\"amount\":50}")).isEmpty();
  }

  @Test
  void shouldSkipConditionEdgeOnFailure() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_B", "CONDITION", "x = 1");
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));

    // success=false → condition edge is always skipped
    assertThat(dagService.resolveNextNodes(1L, "NODE_A", false, "{}")).isEmpty();
  }

  @Test
  void shouldReturnEndTypeWhenNextNodeNotInMapper() {
    WorkflowEdgeEntity edge = edge("NODE_A", "NODE_UNKNOWN", "ALWAYS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "NODE_A")).thenReturn(List.of(edge));
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_UNKNOWN")).thenReturn(null);

    List<WorkflowDagService.DagNodeResolution> result =
        dagService.resolveNextNodes(1L, "NODE_A", true, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).nodeCode()).isEqualTo("NODE_UNKNOWN");
    assertThat(result.get(0).nodeType()).isEqualTo(WorkflowNodeType.END.code());
  }

  // ── isNodeReadyForDispatch ────────────────────────────────────────────────

  @Test
  void shouldReturnFalseWhenWorkflowRunIdIsNull() {
    assertThat(dagService.isNodeReadyForDispatch(null, 1L, "NODE_A", null)).isFalse();
  }

  @Test
  void shouldReturnFalseWhenDefinitionIdIsNull() {
    assertThat(dagService.isNodeReadyForDispatch(1L, null, "NODE_A", null)).isFalse();
  }

  @Test
  void shouldReturnFalseWhenNodeCodeIsBlank() {
    assertThat(dagService.isNodeReadyForDispatch(1L, 1L, "", null)).isFalse();
  }

  @Test
  void shouldReturnTrueWhenNoIncomingEdges() {
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(any(), anyString()))
        .thenReturn(node("NODE_A", WorkflowNodeType.TASK.code()));
    when(edgeMapper.selectIncomingEdges(1L, "NODE_A")).thenReturn(List.of());

    assertThat(dagService.isNodeReadyForDispatch(10L, 1L, "NODE_A", null)).isTrue();
  }

  @Test
  void shouldReturnTrueForAllJoinWhenAllPredecessorsSucceeded() {
    WorkflowEdgeEntity edge1 = edge("PRED_1", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity edge2 = edge("PRED_2", "NODE_A", "SUCCESS", null);
    when(edgeMapper.selectIncomingEdges(1L, "NODE_A")).thenReturn(List.of(edge1, edge2));
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_A"))
        .thenReturn(node("NODE_A", WorkflowNodeType.TASK.code())); // no nodeParams → ALL mode

    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "PRED_1"))
        .thenReturn(nodeRun("PRED_1", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "PRED_2"))
        .thenReturn(nodeRun("PRED_2", "SUCCESS"));

    assertThat(dagService.isNodeReadyForDispatch(10L, 1L, "NODE_A", null)).isTrue();
  }

  @Test
  void shouldReturnFalseForAllJoinWhenOnePredecessorNotTerminal() {
    WorkflowEdgeEntity edge1 = edge("PRED_1", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity edge2 = edge("PRED_2", "NODE_A", "SUCCESS", null);
    when(edgeMapper.selectIncomingEdges(1L, "NODE_A")).thenReturn(List.of(edge1, edge2));
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_A"))
        .thenReturn(node("NODE_A", WorkflowNodeType.TASK.code()));

    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "PRED_1"))
        .thenReturn(nodeRun("PRED_1", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "PRED_2"))
        .thenReturn(null); // not yet started

    assertThat(dagService.isNodeReadyForDispatch(10L, 1L, "NODE_A", null)).isFalse();
  }

  @Test
  void shouldReturnTrueForAnyJoinWhenAtLeastOnePredecessorMatches() {
    WorkflowEdgeEntity edge1 = edge("PRED_1", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity edge2 = edge("PRED_2", "NODE_A", "SUCCESS", null);
    when(edgeMapper.selectIncomingEdges(1L, "NODE_A")).thenReturn(List.of(edge1, edge2));

    WorkflowNodeEntity joinNode = node("NODE_A", WorkflowNodeType.TASK.code());
    joinNode.setNodeParams("{\"joinMode\":\"ANY\"}");
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_A")).thenReturn(joinNode);

    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "PRED_1"))
        .thenReturn(nodeRun("PRED_1", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "PRED_2")).thenReturn(null);

    assertThat(dagService.isNodeReadyForDispatch(10L, 1L, "NODE_A", null)).isTrue();
  }

  @Test
  void shouldReturnTrueForNOfJoinWhenThresholdMet() {
    WorkflowEdgeEntity e1 = edge("P1", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity e2 = edge("P2", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity e3 = edge("P3", "NODE_A", "SUCCESS", null);
    when(edgeMapper.selectIncomingEdges(1L, "NODE_A")).thenReturn(List.of(e1, e2, e3));

    WorkflowNodeEntity joinNode = node("NODE_A", WorkflowNodeType.TASK.code());
    joinNode.setNodeParams("{\"joinMode\":\"N_OF\",\"joinThreshold\":2}");
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_A")).thenReturn(joinNode);

    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "P1"))
        .thenReturn(nodeRun("P1", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "P2"))
        .thenReturn(nodeRun("P2", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "P3")).thenReturn(null);

    assertThat(dagService.isNodeReadyForDispatch(10L, 1L, "NODE_A", null)).isTrue();
  }

  @Test
  void shouldReturnFalseForNOfJoinWhenThresholdNotMet() {
    WorkflowEdgeEntity e1 = edge("P1", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity e2 = edge("P2", "NODE_A", "SUCCESS", null);
    WorkflowEdgeEntity e3 = edge("P3", "NODE_A", "SUCCESS", null);
    when(edgeMapper.selectIncomingEdges(1L, "NODE_A")).thenReturn(List.of(e1, e2, e3));

    WorkflowNodeEntity joinNode = node("NODE_A", WorkflowNodeType.TASK.code());
    joinNode.setNodeParams("{\"joinMode\":\"N_OF\",\"joinThreshold\":3}");
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NODE_A")).thenReturn(joinNode);

    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "P1"))
        .thenReturn(nodeRun("P1", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "P2"))
        .thenReturn(nodeRun("P2", "SUCCESS"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "P3")).thenReturn(null);

    assertThat(dagService.isNodeReadyForDispatch(10L, 1L, "NODE_A", null)).isFalse();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static WorkflowEdgeEntity edge(
      String from, String to, String edgeType, String conditionExpr) {
    WorkflowEdgeEntity e = new WorkflowEdgeEntity();
    e.setFromNodeCode(from);
    e.setToNodeCode(to);
    e.setEdgeType(edgeType);
    e.setConditionExpr(conditionExpr);
    return e;
  }

  private static WorkflowNodeEntity node(String nodeCode, String nodeType) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setNodeCode(nodeCode);
    n.setNodeType(nodeType);
    return n;
  }

  private static WorkflowNodeRunEntity nodeRun(String nodeCode, String status) {
    WorkflowNodeRunEntity run = new WorkflowNodeRunEntity();
    run.setNodeCode(nodeCode);
    run.setNodeStatus(status);
    return run;
  }
}
