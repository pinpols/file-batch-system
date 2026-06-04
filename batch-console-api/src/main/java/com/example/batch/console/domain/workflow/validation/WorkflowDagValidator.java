package com.example.batch.console.domain.workflow.validation;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest.EdgeItem;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest.NodeItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Workflow DAG designer 全量替换的 BE 兜底拓扑校验(MVP)。
 *
 * <p>FE 已做 client-side validators,但 BE 是数据真相源:防绕过 / 防恶意输入 / 防工具脚本写脏。校验在持久化前同步执行,任何 违反规则直接抛 {@link
 * BizException} (VALIDATION_ERROR),不会进入 nodeMapper / edgeMapper。
 *
 * <p>范围边界:仅做 <b>拓扑 + 引用完整性</b>,不做业务对错(ADR-021)/ 性能优化(N+1,MVP 节点 ≤ 200 量级)/ 高级图分析(关键路径 / 复杂度评分)。
 *
 * <p>规则清单(详见 docs/design/workflow-dag-designer.md):
 *
 * <ol>
 *   <li>节点数 ≤ 200(防恶意大输入,可调)
 *   <li>nodeCode 唯一(同一 workflow 内)
 *   <li>恰好 1 个 START 节点
 *   <li>至少 1 个 END 节点
 *   <li>边 from/to 必须是已知节点
 *   <li>无环(Kahn 拓扑排序)
 *   <li>无孤立节点(每个非 START 节点必须能从 START DFS 到达)
 *   <li>JOB.related_job_code 非空
 *   <li>FILE_STEP.related_pipeline_code 非空 + 必须在 pipeline_definition 表存在(同租户)
 *   <li>GATEWAY 出度 ≥ 2
 *   <li>GATEWAY.gateway_strategy 非空(承载在 node_params,MVP 阶段 string 非空即可)
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class WorkflowDagValidator {

  /** 节点数硬上限,防恶意大输入打爆 BFS。MVP 量级,后续可调。 */
  public static final int MAX_NODES = 200;

  private final PipelineDefinitionMapper pipelineDefinitionMapper;

  /**
   * 校验全量替换请求的 DAG 拓扑 + 引用完整性。任何规则违反 → 抛 {@link BizException}。
   *
   * @param tenantId 已解析的租户 id(由 caller 走 tenantGuard 拿到)
   * @param request 完整的 (definition + nodes + edges)
   */
  public void validate(String tenantId, WorkflowDefinitionSaveRequest request) {
    List<NodeItem> nodes = request.getNodes() == null ? List.of() : request.getNodes();
    List<EdgeItem> edges = request.getEdges() == null ? List.of() : request.getEdges();

    // 1. 节点数上限
    if (nodes.size() > MAX_NODES) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR,
          "error.workflow.dag.too_many_nodes",
          nodes.size(),
          MAX_NODES);
    }

    // 2. nodeCode 唯一 + 收集节点信息
    Set<String> nodeCodes = new HashSet<>();
    List<String> startNodes = new ArrayList<>();
    List<String> endNodes = new ArrayList<>();
    Map<String, NodeItem> byCode = new HashMap<>();
    for (NodeItem n : nodes) {
      String code = n.getNodeCode();
      if (!nodeCodes.add(code)) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR, "error.workflow.dag.duplicate_node_code", code);
      }
      byCode.put(code, n);
      String type = n.getNodeType();
      if (WorkflowNodeType.START.code().equalsIgnoreCase(type)) {
        startNodes.add(code);
      } else if (WorkflowNodeType.END.code().equalsIgnoreCase(type)) {
        endNodes.add(code);
      }
    }

    // 3/4. START / END 数量
    if (startNodes.size() != 1) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR, "error.workflow.dag.start_count_invalid", startNodes.size());
    }
    if (endNodes.isEmpty()) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.dag.missing_end");
    }

    // 5. 边端点必须存在
    for (EdgeItem e : edges) {
      if (!nodeCodes.contains(e.getFromNodeCode())) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR,
            "error.workflow.dag.edge_from_unknown",
            e.getFromNodeCode(),
            e.getToNodeCode());
      }
      if (!nodeCodes.contains(e.getToNodeCode())) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR,
            "error.workflow.dag.edge_to_unknown",
            e.getFromNodeCode(),
            e.getToNodeCode());
      }
    }

    // 构建邻接表 + 出度统计
    Map<String, List<String>> adj = new HashMap<>();
    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, Integer> outDegree = new HashMap<>();
    for (String code : nodeCodes) {
      adj.put(code, new ArrayList<>());
      inDegree.put(code, 0);
      outDegree.put(code, 0);
    }
    for (EdgeItem e : edges) {
      adj.get(e.getFromNodeCode()).add(e.getToNodeCode());
      inDegree.merge(e.getToNodeCode(), 1, Integer::sum);
      outDegree.merge(e.getFromNodeCode(), 1, Integer::sum);
    }

    // 6. 无环 — Kahn 拓扑排序;visited < total 即存在环
    detectCycle(nodeCodes, adj, inDegree);

    // 7. 无孤立节点 — 从 START BFS,每个非 START 节点必须可达
    detectUnreachable(startNodes.get(0), nodeCodes, adj);

    // 8/9/10/11. 节点字段引用完整性
    for (NodeItem n : nodes) {
      String type = n.getNodeType();
      if (WorkflowNodeType.JOB.code().equalsIgnoreCase(type)) {
        if (isBlank(n.getRelatedJobCode())) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.job_related_code_missing",
              n.getNodeCode());
        }
      } else if (WorkflowNodeType.FILE_STEP.code().equalsIgnoreCase(type)) {
        String pipelineCode = n.getRelatedPipelineCode();
        if (isBlank(pipelineCode)) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.file_step_related_pipeline_missing",
              n.getNodeCode());
        }
        long cnt = pipelineDefinitionMapper.countByJobCode(tenantId, pipelineCode);
        if (cnt == 0) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.file_step_pipeline_not_found",
              n.getNodeCode(),
              pipelineCode);
        }
      } else if (WorkflowNodeType.GATEWAY.code().equalsIgnoreCase(type)) {
        // gateway_strategy 承载在 node_params (JSON);MVP 阶段非空即可,具体 strategy 取值不校验
        if (isBlank(n.getNodeParams())) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.gateway_strategy_missing",
              n.getNodeCode());
        }
        if (outDegree.getOrDefault(n.getNodeCode(), 0) < 2) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.gateway_out_degree_too_small",
              n.getNodeCode(),
              outDegree.getOrDefault(n.getNodeCode(), 0));
        }
      }
    }
  }

  private static void detectCycle(
      Set<String> nodeCodes, Map<String, List<String>> adj, Map<String, Integer> inDegree) {
    Deque<String> queue = new ArrayDeque<>();
    Map<String, Integer> deg = new HashMap<>(inDegree);
    for (Map.Entry<String, Integer> e : deg.entrySet()) {
      if (e.getValue() == 0) {
        queue.add(e.getKey());
      }
    }
    int visited = 0;
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      visited++;
      for (String next : adj.get(cur)) {
        int v = deg.get(next) - 1;
        deg.put(next, v);
        if (v == 0) {
          queue.add(next);
        }
      }
    }
    if (visited < nodeCodes.size()) {
      String stuck =
          deg.entrySet().stream()
              .filter(e -> e.getValue() > 0)
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse("?");
      throw BizException.of(
          ResultCode.VALIDATION_ERROR, "error.workflow.dag.cycle_detected", stuck);
    }
  }

  private static void detectUnreachable(
      String startCode, Set<String> nodeCodes, Map<String, List<String>> adj) {
    Set<String> reachable = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(startCode);
    reachable.add(startCode);
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      for (String next : adj.get(cur)) {
        if (reachable.add(next)) {
          queue.add(next);
        }
      }
    }
    for (String code : nodeCodes) {
      if (!reachable.contains(code)) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR, "error.workflow.dag.node_unreachable", code);
      }
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
