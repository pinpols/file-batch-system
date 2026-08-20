package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 负责已保存工作流的 DAG 诊断。
 *
 * <p>该检查与写入前的 {@code WorkflowDagValidator} 目的不同：它需要把数据库中当前拓扑和 JOB 引用转换为可供
 * Console 定位节点/边的结构化 findings。它只读数据库，不负责修改定义、发布事件或裁定业务语义。
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
public final class WorkflowDefinitionDagInspector {

  private final JobDefinitionMapper jobDefinitionMapper;

  public List<DagValidationResult.Finding> inspect(
      String tenantId, List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
    List<DagValidationResult.Finding> findings = new ArrayList<>();
    Set<String> nodeCodes = new HashSet<>();
    List<String> startNodes = new ArrayList<>();
    List<String> endNodes = new ArrayList<>();

    for (WorkflowNodeEntity node : nodes) {
      nodeCodes.add(node.getNodeCode());
      if ("START".equalsIgnoreCase(node.getNodeType())) {
        startNodes.add(node.getNodeCode());
      }
      if ("END".equalsIgnoreCase(node.getNodeType())) {
        endNodes.add(node.getNodeCode());
      }
    }

    validateNodeReferences(findings, nodeCodes, startNodes, endNodes, edges);
    validateJobNodeReferences(findings, tenantId, nodes);
    validateConditionEdges(findings, edges);
    DagAdjacency dag = buildAdjacency(nodeCodes, edges);
    detectCycles(findings, dag, nodeCodes);
    validateReachability(findings, nodes, nodeCodes, startNodes, endNodes, dag);
    return findings;
  }

  // JOB 节点必须引用当前租户下存在且启用的 job_definition。
  private void validateJobNodeReferences(
      List<DagValidationResult.Finding> findings, String tenantId, List<WorkflowNodeEntity> nodes) {
    for (WorkflowNodeEntity node : nodes) {
      if (!"JOB".equalsIgnoreCase(node.getNodeType())) {
        continue;
      }
      String jobCode = node.getRelatedJobCode();
      if (EmptyChecks.isBlank(jobCode)) {
        findings.add(DagValidationResult.Finding.error(
            "JOB_REF_MISSING",
            "JOB node missing related_job_code: " + node.getNodeCode(),
            node.getNodeCode(),
            null));
        continue;
      }
      JobDefinitionEntity jobDefinition = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
      if (jobDefinition == null) {
        findings.add(DagValidationResult.Finding.error(
            "JOB_REF_NOT_FOUND",
            "JOB node " + node.getNodeCode() + " references non-existent job_definition: "
                + jobCode,
            node.getNodeCode(),
            null));
        continue;
      }
      if (Boolean.FALSE.equals(jobDefinition.getEnabled())) {
        findings.add(DagValidationResult.Finding.error(
            "JOB_REF_DISABLED",
            "JOB node " + node.getNodeCode() + " references disabled job_definition: " + jobCode,
            node.getNodeCode(),
            null));
      }
    }
  }

  // CONDITION 边必须带表达式；表达式内容本身属于业务配置，不在此处解释。
  private void validateConditionEdges(
      List<DagValidationResult.Finding> findings, List<WorkflowEdgeEntity> edges) {
    for (WorkflowEdgeEntity edge : edges) {
      if (!"CONDITION".equalsIgnoreCase(edge.getEdgeType())) {
        continue;
      }
      if (EmptyChecks.isBlank(edge.getConditionExpr())) {
        findings.add(DagValidationResult.Finding.error(
            "EDGE_CONDITION_MISSING_EXPR",
            "CONDITION edge missing condition_expr: " + edge.getFromNodeCode() + " -> "
                + edge.getToNodeCode(),
            null,
            edgeIdOf(edge)));
      }
    }
  }

  private void validateNodeReferences(
      List<DagValidationResult.Finding> findings,
      Set<String> nodeCodes,
      List<String> startNodes,
      List<String> endNodes,
      List<WorkflowEdgeEntity> edges) {
    if (EmptyChecks.isEmpty(startNodes)) {
      findings.add(
          DagValidationResult.Finding.error("MISSING_START", "Missing START node", null, null));
    } else if (startNodes.size() > 1) {
      for (int i = 1; i < startNodes.size(); i++) {
        findings.add(DagValidationResult.Finding.error(
            "MULTIPLE_START",
            "Multiple START nodes found: " + startNodes,
            startNodes.get(i),
            null));
      }
    }

    if (EmptyChecks.isEmpty(endNodes)) {
      findings.add(
          DagValidationResult.Finding.error("MISSING_END", "Missing END node", null, null));
    } else if (endNodes.size() > 1) {
      for (int i = 1; i < endNodes.size(); i++) {
        findings.add(DagValidationResult.Finding.error(
            "MULTIPLE_END", "Multiple END nodes found: " + endNodes, endNodes.get(i), null));
      }
    }

    for (WorkflowEdgeEntity edge : edges) {
      if (!nodeCodes.contains(edge.getFromNodeCode())) {
        findings.add(DagValidationResult.Finding.error(
            "EDGE_SOURCE_MISSING",
            "Edge references non-existent source node: " + edge.getFromNodeCode(),
            null,
            edgeIdOf(edge)));
      }
      if (!nodeCodes.contains(edge.getToNodeCode())) {
        findings.add(DagValidationResult.Finding.error(
            "EDGE_TARGET_MISSING",
            "Edge references non-existent target node: " + edge.getToNodeCode(),
            null,
            edgeIdOf(edge)));
      }
    }
  }

  private static String edgeIdOf(WorkflowEdgeEntity edge) {
    return edge.getFromNodeCode() + "-" + edge.getToNodeCode() + "-" + edge.getEdgeType();
  }

  private DagAdjacency buildAdjacency(Set<String> nodeCodes, List<WorkflowEdgeEntity> edges) {
    Map<String, List<String>> adjacency = new HashMap<>();
    Map<String, List<String>> reverseAdjacency = new HashMap<>();
    Map<String, Integer> inDegree = new HashMap<>();
    for (String code : nodeCodes) {
      adjacency.put(code, new ArrayList<>());
      reverseAdjacency.put(code, new ArrayList<>());
      inDegree.put(code, 0);
    }
    for (WorkflowEdgeEntity edge : edges) {
      if (nodeCodes.contains(edge.getFromNodeCode()) && nodeCodes.contains(edge.getToNodeCode())) {
        adjacency.get(edge.getFromNodeCode()).add(edge.getToNodeCode());
        reverseAdjacency.get(edge.getToNodeCode()).add(edge.getFromNodeCode());
        inDegree.merge(edge.getToNodeCode(), 1, Integer::sum);
      }
    }
    return new DagAdjacency(adjacency, reverseAdjacency, inDegree);
  }

  private void detectCycles(
      List<DagValidationResult.Finding> findings, DagAdjacency dag, Set<String> nodeCodes) {
    Deque<String> queue = new ArrayDeque<>();
    Map<String, Integer> inDegree = new HashMap<>(dag.inDegree());
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
      }
    }
    int visited = 0;
    while (EmptyChecks.isNotEmpty(queue)) {
      String current = queue.poll();
      visited++;
      for (String next : dag.adjacency().get(current)) {
        int degree = inDegree.get(next) - 1;
        inDegree.put(next, degree);
        if (degree == 0) {
          queue.add(next);
        }
      }
    }
    if (visited < nodeCodes.size()) {
      for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
        if (entry.getValue() > 0) {
          findings.add(DagValidationResult.Finding.error(
              "CYCLE_DETECTED",
              "Cycle detected in workflow DAG (node still has incoming edges)",
              entry.getKey(),
              null));
        }
      }
    }
  }

  private void validateReachability(
      List<DagValidationResult.Finding> findings,
      List<WorkflowNodeEntity> nodes,
      Set<String> nodeCodes,
      List<String> startNodes,
      List<String> endNodes,
      DagAdjacency dag) {
    if (startNodes.size() == 1) {
      Set<String> reachableFromStart = new HashSet<>();
      bfs(startNodes.get(0), dag.adjacency(), reachableFromStart);
      for (String code : nodeCodes) {
        if (!"START".equalsIgnoreCase(nodeTypeByCode(nodes, code))
            && !reachableFromStart.contains(code)) {
          findings.add(DagValidationResult.Finding.error(
              "UNREACHABLE_FROM_START", "Node not reachable from START: " + code, code, null));
        }
      }
    }

    if (endNodes.size() == 1) {
      Set<String> reachableToEnd = new HashSet<>();
      bfs(endNodes.get(0), dag.reverseAdjacency(), reachableToEnd);
      for (String code : nodeCodes) {
        if (!"END".equalsIgnoreCase(nodeTypeByCode(nodes, code))
            && !reachableToEnd.contains(code)) {
          findings.add(DagValidationResult.Finding.error(
              "CANNOT_REACH_END", "Node cannot reach END: " + code, code, null));
        }
      }
    }
  }

  private void bfs(String start, Map<String, List<String>> adjacency, Set<String> visited) {
    Deque<String> queue = new ArrayDeque<>();
    queue.add(start);
    visited.add(start);
    while (EmptyChecks.isNotEmpty(queue)) {
      String current = queue.poll();
      for (String next : adjacency.getOrDefault(current, List.of())) {
        if (visited.add(next)) {
          queue.add(next);
        }
      }
    }
  }

  private String nodeTypeByCode(List<WorkflowNodeEntity> nodes, String code) {
    for (WorkflowNodeEntity node : nodes) {
      if (node.getNodeCode().equals(code)) {
        return node.getNodeType();
      }
    }
    return null;
  }

  private record DagAdjacency(
      Map<String, List<String>> adjacency,
      Map<String, List<String>> reverseAdjacency,
      Map<String, Integer> inDegree) {}
}
