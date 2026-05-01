package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

  // ── cascadeSkipDownstream ─────────────────────────────────────────────────

  @Test
  void cascadeSkipShouldReturnEmptyOnNullInputs() {
    assertThat(dagService.cascadeSkipDownstream(null, 1L, "X")).isEmpty();
    assertThat(dagService.cascadeSkipDownstream(10L, null, "X")).isEmpty();
    assertThat(dagService.cascadeSkipDownstream(10L, 1L, " ")).isEmpty();
  }

  @Test
  void cascadeSkipShouldWriteSkippedRowForUnreachableSuccessEdgeDownstream() {
    // FAIL_NODE --SUCCESS--> NEXT (NEXT 仅此一条入边，FAIL_NODE 已 FAILED → NEXT 永远无法触发)
    WorkflowEdgeEntity outgoing = edge("FAIL_NODE", "NEXT", "SUCCESS", null);
    WorkflowEdgeEntity incoming = edge("FAIL_NODE", "NEXT", "SUCCESS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "FAIL_NODE")).thenReturn(List.of(outgoing));
    when(edgeMapper.selectIncomingEdges(1L, "NEXT")).thenReturn(List.of(incoming));
    when(edgeMapper.selectOutgoingEdges(1L, "NEXT")).thenReturn(List.of());
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "FAIL_NODE"))
        .thenReturn(nodeRun("FAIL_NODE", "FAILED"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "NEXT")).thenReturn(null);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(1L, "NEXT"))
        .thenReturn(node("NEXT", WorkflowNodeType.TASK.code()));

    List<String> skipped = dagService.cascadeSkipDownstream(10L, 1L, "FAIL_NODE");

    assertThat(skipped).containsExactly("NEXT");
    verify(nodeRunMapper).insert(any());
  }

  @Test
  void cascadeSkipShouldNotSkipWhenJoinNodeStillHasLiveSuccessUpstream() {
    // FAIL_NODE --SUCCESS--> JOIN
    // OK_NODE  --SUCCESS--> JOIN  (OK_NODE 还 RUNNING，JOIN 不应被预先 skip)
    WorkflowEdgeEntity outgoing = edge("FAIL_NODE", "JOIN", "SUCCESS", null);
    WorkflowEdgeEntity inFromFail = edge("FAIL_NODE", "JOIN", "SUCCESS", null);
    WorkflowEdgeEntity inFromOk = edge("OK_NODE", "JOIN", "SUCCESS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "FAIL_NODE")).thenReturn(List.of(outgoing));
    when(edgeMapper.selectIncomingEdges(1L, "JOIN")).thenReturn(List.of(inFromFail, inFromOk));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "FAIL_NODE"))
        .thenReturn(nodeRun("FAIL_NODE", "FAILED"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "OK_NODE"))
        .thenReturn(nodeRun("OK_NODE", "RUNNING"));

    List<String> skipped = dagService.cascadeSkipDownstream(10L, 1L, "FAIL_NODE");

    assertThat(skipped).isEmpty();
    verify(nodeRunMapper, never()).insert(any());
  }

  @Test
  void cascadeSkipShouldNotSkipWhenFailureEdgeMatches() {
    // FAIL_NODE --FAILURE--> CATCH (FAILURE 边匹配 FAILED 上游，CATCH 仍可正常派发)
    WorkflowEdgeEntity outgoing = edge("FAIL_NODE", "CATCH", "FAILURE", null);
    when(edgeMapper.selectOutgoingEdges(1L, "FAIL_NODE")).thenReturn(List.of(outgoing));

    List<String> skipped = dagService.cascadeSkipDownstream(10L, 1L, "FAIL_NODE");

    assertThat(skipped).isEmpty();
    verify(nodeRunMapper, never()).insert(any());
  }

  @Test
  void cascadeSkipShouldRecurseThroughChain() {
    // FAIL_NODE --SUCCESS--> A --SUCCESS--> B (两条 SUCCESS 边，FAIL_NODE FAILED → A、B 都 skip)
    WorkflowEdgeEntity failToA = edge("FAIL_NODE", "A", "SUCCESS", null);
    WorkflowEdgeEntity aToB = edge("A", "B", "SUCCESS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "FAIL_NODE")).thenReturn(List.of(failToA));
    when(edgeMapper.selectOutgoingEdges(1L, "A")).thenReturn(List.of(aToB));
    when(edgeMapper.selectOutgoingEdges(1L, "B")).thenReturn(List.of());
    when(edgeMapper.selectIncomingEdges(1L, "A")).thenReturn(List.of(failToA));
    when(edgeMapper.selectIncomingEdges(1L, "B")).thenReturn(List.of(aToB));

    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "FAIL_NODE"))
        .thenReturn(nodeRun("FAIL_NODE", "FAILED"));
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "A"))
        .thenReturn(null) // canNeverFire 时 A 还没 node_run
        .thenReturn(nodeRun("A", "SKIPPED")); // 级联到 B 时 A 已被插为 SKIPPED
    when(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "B")).thenReturn(null);
    when(nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(any(), anyString()))
        .thenReturn(node("DUMMY", WorkflowNodeType.TASK.code()));

    List<String> skipped = dagService.cascadeSkipDownstream(10L, 1L, "FAIL_NODE");

    assertThat(skipped).containsExactly("A", "B");
  }

  @Test
  void cascadeSkipShouldNotTouchEndNode() {
    // FAIL_NODE --SUCCESS--> END (END 节点不写 SKIPPED 行，由调度路径处理)
    WorkflowEdgeEntity outgoing = edge("FAIL_NODE", "END", "SUCCESS", null);
    when(edgeMapper.selectOutgoingEdges(1L, "FAIL_NODE")).thenReturn(List.of(outgoing));

    List<String> skipped = dagService.cascadeSkipDownstream(10L, 1L, "FAIL_NODE");

    assertThat(skipped).isEmpty();
    verify(nodeRunMapper, never()).insert(any());
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
