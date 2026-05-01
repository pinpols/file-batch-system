package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowJoinMode;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DAG 解析层：负责工作流节点间的可达性判定，解耦状态机与拓扑。
 *
 * <p>两个方向：
 *
 * <ul>
 *   <li><b>下游（outgoing）</b>：{@link #resolveInitialNodes} / {@link #resolveNextNodes} 按 edge 类型 筛选可
 *       dispatch 的下游节点。edge 类型 {@code ALWAYS / SUCCESS / FAILURE / CONDITION} 决定筛选逻辑（CONDITION 走
 *       {@link WorkflowConditionEvaluator} 解析表达式）。
 *   <li><b>上游（incoming）</b>：{@link #isNodeReadyForDispatch} 校验所有前驱 node_run 达到终态 （{@code SUCCESS /
 *       FAILED / SKIPPED}）且满足 join 规则后方可派发。
 * </ul>
 *
 * <p>Join 规则（{@code ALL / ANY / N_OF}）写入 {@code workflow_node.node_params} 的 {@code joinMode}
 * 字段，避免为 join 配置单独扩一张表；详见 {@link #resolveJoinRule}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWorkflowDagService implements WorkflowDagService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String STATUS_SUCCESS = "SUCCESS";

  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final WorkflowConditionEvaluator workflowConditionEvaluator;

  @Override
  public List<DagNodeResolution> resolveInitialNodes(
      Long workflowDefinitionId, String payloadJson) {
    return resolveNextNodes(workflowDefinitionId, WorkflowNodeCode.START.code(), true, payloadJson);
  }

  @Override
  public List<DagNodeResolution> resolveNextNodes(
      Long workflowDefinitionId, String currentNodeCode, boolean success, String payloadJson) {
    if (workflowDefinitionId == null || currentNodeCode == null || currentNodeCode.isBlank()) {
      return List.of();
    }
    List<WorkflowEdgeEntity> outgoingEdges =
        workflowEdgeMapper.selectOutgoingEdges(workflowDefinitionId, currentNodeCode);
    if (outgoingEdges == null || outgoingEdges.isEmpty()) {
      return List.of();
    }
    List<DagNodeResolution> resolutions = new ArrayList<>();
    for (WorkflowEdgeEntity edge : outgoingEdges) {
      if (!matchesOutgoingEdge(edge, success, payloadJson)) {
        continue;
      }
      WorkflowNodeEntity nextNode =
          workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
              workflowDefinitionId, edge.getToNodeCode());
      if (nextNode == null) {
        resolutions.add(new DagNodeResolution(edge.getToNodeCode(), WorkflowNodeType.END.code()));
        continue;
      }
      resolutions.add(new DagNodeResolution(nextNode.getNodeCode(), nextNode.getNodeType()));
    }
    return resolutions;
  }

  @Override
  public boolean isNodeReadyForDispatch(
      Long workflowRunId, Long workflowDefinitionId, String nodeCode, String payloadJson) {
    if (workflowRunId == null
        || workflowDefinitionId == null
        || nodeCode == null
        || nodeCode.isBlank()) {
      return false;
    }
    WorkflowNodeEntity currentNode =
        workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(workflowDefinitionId, nodeCode);
    // A-3.4: currentNode 为 null 说明节点被中途删除，若贸然继续会在 resolveJoinRule
    // 上 NPE；保守返回 false 避免幽灵节点被派发。
    if (currentNode == null) {
      log.warn(
          "workflow node definition missing during dispatch readiness check:"
              + " workflowRunId={}, workflowDefinitionId={}, nodeCode={} — node may have been"
              + " deleted mid-run, refusing dispatch",
          workflowRunId,
          workflowDefinitionId,
          nodeCode);
      return false;
    }
    List<WorkflowEdgeEntity> incomingEdges =
        workflowEdgeMapper.selectIncomingEdges(workflowDefinitionId, nodeCode);
    if (incomingEdges == null || incomingEdges.isEmpty()) {
      // A-3.4: 无入边的节点正常情况下只能是 START；出现非-START 无入边节点
      // 说明 workflow_edge 在 run 期间被改过（软删 / 定义变更），记 WARN 便于排查。
      // 保持"视为就绪"的现有语义以避免工作流死锁，但运维应能从日志发现异常。
      if (!WorkflowNodeType.START.code().equalsIgnoreCase(currentNode.getNodeType())) {
        log.warn(
            "non-START node {} has no incoming edges: workflowRunId={}, definitionId={} —"
                + " workflow_edge may have been mutated after workflow_run started",
            nodeCode,
            workflowRunId,
            workflowDefinitionId);
      }
      return true;
    }
    int matchedCount = 0;
    int terminalCount = 0;
    for (WorkflowEdgeEntity edge : incomingEdges) {
      var predecessorRun =
          workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
              workflowRunId, edge.getFromNodeCode());
      if (predecessorRun == null || !isTerminal(predecessorRun.getNodeStatus())) {
        continue;
      }
      terminalCount++;
      if (matchesIncomingEdge(edge, predecessorRun.getNodeStatus(), payloadJson)) {
        matchedCount++;
      }
    }
    JoinRule joinRule = resolveJoinRule(currentNode, incomingEdges.size());
    return switch (joinRule.joinMode()) {
      case ALL -> terminalCount == incomingEdges.size() && matchedCount == incomingEdges.size();
      case ANY -> matchedCount >= 1;
      case N_OF -> matchedCount >= joinRule.joinThreshold();
    };
  }

  @Override
  public List<String> cascadeSkipDownstream(
      Long workflowRunId, Long workflowDefinitionId, String fromNodeCode) {
    if (workflowRunId == null
        || workflowDefinitionId == null
        || fromNodeCode == null
        || fromNodeCode.isBlank()) {
      return List.of();
    }
    Deque<String> queue = new ArrayDeque<>();
    queue.add(fromNodeCode);
    Set<String> visited = new HashSet<>();
    List<String> skipped = new ArrayList<>();
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      if (!visited.add(cur)) {
        continue;
      }
      List<WorkflowEdgeEntity> outgoing =
          workflowEdgeMapper.selectOutgoingEdges(workflowDefinitionId, cur);
      if (outgoing == null || outgoing.isEmpty()) {
        continue;
      }
      for (WorkflowEdgeEntity edge : outgoing) {
        String to = edge.getToNodeCode();
        if (to == null || to.isBlank() || WorkflowNodeCode.END.code().equalsIgnoreCase(to)) {
          continue;
        }
        if (!canNeverFire(workflowRunId, workflowDefinitionId, to)) {
          continue;
        }
        if (insertSkippedNodeRunIfAbsent(workflowRunId, workflowDefinitionId, to)) {
          skipped.add(to);
          queue.add(to);
        }
      }
    }
    if (!skipped.isEmpty()) {
      log.info(
          "cascade-skipped {} downstream node(s) of {} on workflow_run {}: {}",
          skipped.size(),
          fromNodeCode,
          workflowRunId,
          skipped);
    }
    return skipped;
  }

  /** 节点是否再无机会触发：所有入边对应的前驱已达终态且没有任何入边匹配。 该谓词同时满足 ALL / ANY / N_OF 三种 join（任意 join 至少要 1 条匹配）。 */
  private boolean canNeverFire(Long workflowRunId, Long workflowDefinitionId, String nodeCode) {
    List<WorkflowEdgeEntity> incoming =
        workflowEdgeMapper.selectIncomingEdges(workflowDefinitionId, nodeCode);
    if (incoming == null || incoming.isEmpty()) {
      return false;
    }
    for (WorkflowEdgeEntity edge : incoming) {
      var pred =
          workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
              workflowRunId, edge.getFromNodeCode());
      if (pred == null || !isTerminal(pred.getNodeStatus())) {
        return false;
      }
      if (matchesIncomingEdge(edge, pred.getNodeStatus(), null)) {
        return false;
      }
    }
    return true;
  }

  /** 同一 workflow_run 已有同 nodeCode 行则跳过；否则插入 SKIPPED 终态行。 */
  private boolean insertSkippedNodeRunIfAbsent(
      Long workflowRunId, Long workflowDefinitionId, String nodeCode) {
    WorkflowNodeRunEntity existing =
        workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
    if (existing != null) {
      return false;
    }
    WorkflowNodeEntity node =
        workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(workflowDefinitionId, nodeCode);
    String nodeType = node == null ? WorkflowNodeType.TASK.code() : node.getNodeType();
    WorkflowNodeRunEntity row = new WorkflowNodeRunEntity();
    row.setWorkflowRunId(workflowRunId);
    row.setNodeCode(nodeCode);
    row.setNodeType(nodeType);
    row.setRunSeq(1);
    row.setNodeStatus("SKIPPED");
    row.setRetryCount(0);
    Instant now = Instant.now();
    row.setStartedAt(now);
    row.setFinishedAt(now);
    row.setDurationMs(0L);
    workflowNodeRunMapper.insert(row);
    return true;
  }

  private boolean matchesOutgoingEdge(
      WorkflowEdgeEntity edge, boolean success, String payloadJson) {
    WorkflowEdgeType type = DictEnum.fromCode(WorkflowEdgeType.class, edge.getEdgeType());
    if (type == null) {
      return false;
    }
    return switch (type) {
      case ALWAYS -> true;
      case SUCCESS -> success;
      case FAILURE -> !success;
      case CONDITION ->
          success && workflowConditionEvaluator.matches(edge.getConditionExpr(), payloadJson);
    };
  }

  private boolean matchesIncomingEdge(
      WorkflowEdgeEntity edge, String predecessorStatus, String payloadJson) {
    WorkflowEdgeType type = DictEnum.fromCode(WorkflowEdgeType.class, edge.getEdgeType());
    if (type == null) {
      return false;
    }
    return switch (type) {
      case ALWAYS -> isTerminal(predecessorStatus);
      case SUCCESS -> STATUS_SUCCESS.equalsIgnoreCase(predecessorStatus);
      case FAILURE -> "FAILED".equalsIgnoreCase(predecessorStatus);
      case CONDITION ->
          STATUS_SUCCESS.equalsIgnoreCase(predecessorStatus)
              && workflowConditionEvaluator.matches(edge.getConditionExpr(), payloadJson);
    };
  }

  private boolean isTerminal(String nodeStatus) {
    return STATUS_SUCCESS.equalsIgnoreCase(nodeStatus)
        || "FAILED".equalsIgnoreCase(nodeStatus)
        || "SKIPPED".equalsIgnoreCase(nodeStatus);
  }

  /** joinMode 写入 workflow_node.node_params，避免为 join 规则单独扩表。 */
  @SuppressWarnings("unchecked")
  private JoinRule resolveJoinRule(WorkflowNodeEntity node, int incomingEdgeCount) {
    if (node == null || node.getNodeParams() == null || node.getNodeParams().isBlank()) {
      return new JoinRule(WorkflowJoinMode.ALL, Math.max(1, incomingEdgeCount));
    }
    try {
      Object nodeParamsObject = JsonUtils.fromJson(node.getNodeParams(), Object.class);
      if (!(nodeParamsObject instanceof Map<?, ?> nodeParamsMap)) {
        return new JoinRule(WorkflowJoinMode.ALL, Math.max(1, incomingEdgeCount));
      }
      Map<String, Object> params = (Map<String, Object>) nodeParamsMap;
      WorkflowJoinMode joinMode = WorkflowJoinMode.fromCode(stringValue(params.get("joinMode")));
      if (joinMode == WorkflowJoinMode.ALL) {
        return new JoinRule(joinMode, Math.max(1, incomingEdgeCount));
      }
      if (joinMode == WorkflowJoinMode.ANY) {
        return new JoinRule(joinMode, 1);
      }
      int threshold = integerValue(params.get("joinThreshold"));
      if (threshold <= 0) {
        threshold = Math.max(1, incomingEdgeCount);
      }
      threshold = Math.min(threshold, Math.max(1, incomingEdgeCount));
      return new JoinRule(joinMode, threshold);
    } catch (IllegalArgumentException exception) {
      return new JoinRule(WorkflowJoinMode.ALL, Math.max(1, incomingEdgeCount));
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private int integerValue(Object value) {
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private record JoinRule(WorkflowJoinMode joinMode, int joinThreshold) {}
}
