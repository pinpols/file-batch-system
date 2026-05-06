package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.workflow.WorkflowValidationResult.ValidationIssue;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.workflow.CrossDayDependencySpec;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADR-025 §决策 — Workflow 静态校验器。
 *
 * <p>{@link #validate(Long)} 在 console enable 路径 + reconciler 调用，返回 {@link
 * WorkflowValidationResult}：errors 非空 → 拒绝 enable；warnings 仅展示。
 *
 * <p>当前覆盖检查项（ADR-025 §校验项清单 Stage 1-3）：
 *
 * <ul>
 *   <li>V1 拓扑成环 / 自环（ERROR）
 *   <li>V2 不可达节点（从 START 走不到，ERROR）
 *   <li>V3 不可终止节点（走不到 END，ERROR）
 *   <li>V4 DSL 引用 nodeCode 不存在（ERROR）
 *   <li>V6 跨日依赖 spec 解析失败（ERROR）
 *   <li>V7 跨日依赖 range 跨度超 90 天（ERROR）
 *   <li>V9 GATEWAY join_mode=ALL_OF 但 incoming<2（ERROR）
 *   <li>V10 GATEWAY join_mode=N_OF_M 但 N>M / M≠incoming（ERROR）
 *   <li>V11 START 节点有 incoming / END 节点有 outgoing（ERROR）
 *   <li>V13 重复 node_code（ERROR）
 *   <li>V14 edge 引用 node_code 不存在（ERROR）
 * </ul>
 *
 * <p>未实现：V5/V12（output contract，依赖 worker SPI）/ V8（OPTIONAL 传染性退化，依赖图层语义全展开）/ V15（timezone 一致性）— 见
 * ADR-025 §实施分阶段 Stage 4。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowGraphValidator {

  private static final Pattern DSL_NODE_REF = Pattern.compile("\\$\\.nodes\\.([A-Za-z0-9_]+)\\.");
  private static final TypeReference<List<CrossDayDependencySpec>> SPEC_LIST_TYPE =
      new TypeReference<>() {};
  private static final int MAX_RANGE_DAYS = 90;

  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;

  public WorkflowValidationResult validate(Long workflowDefinitionId) {
    if (workflowDefinitionId == null) {
      return WorkflowValidationResult.clean();
    }
    List<WorkflowNodeEntity> nodes =
        workflowNodeMapper.selectByWorkflowDefinitionId(workflowDefinitionId);
    List<WorkflowEdgeEntity> edges =
        workflowEdgeMapper.selectAllByWorkflowDefinitionId(workflowDefinitionId);
    if (nodes == null) nodes = List.of();
    if (edges == null) edges = List.of();

    List<ValidationIssue> errors = new ArrayList<>();
    List<ValidationIssue> warnings = new ArrayList<>();

    Map<String, WorkflowNodeEntity> byCode = new LinkedHashMap<>();
    Set<String> duplicates = new HashSet<>();
    for (WorkflowNodeEntity n : nodes) {
      if (n == null || !Texts.hasText(n.getNodeCode())) continue;
      WorkflowNodeEntity prior = byCode.put(n.getNodeCode(), n);
      if (prior != null) duplicates.add(n.getNodeCode());
    }

    // V13 重复 node_code
    for (String code : duplicates) {
      errors.add(issue("V13", "duplicate node_code", code));
    }

    // V14 edge 引用不存在的节点
    for (WorkflowEdgeEntity e : edges) {
      if (e == null) continue;
      if (Texts.hasText(e.getFromNodeCode()) && !byCode.containsKey(e.getFromNodeCode())) {
        errors.add(
            issue(
                "V14",
                "edge from_node_code references missing node: " + e.getFromNodeCode(),
                e.getFromNodeCode()));
      }
      if (Texts.hasText(e.getToNodeCode()) && !byCode.containsKey(e.getToNodeCode())) {
        errors.add(
            issue(
                "V14",
                "edge to_node_code references missing node: " + e.getToNodeCode(),
                e.getToNodeCode()));
      }
    }

    // 邻接矩阵
    Map<String, List<String>> outgoing = buildAdjacency(edges, true);
    Map<String, List<String>> incoming = buildAdjacency(edges, false);

    // V1 cycle / self-loop
    detectCycles(byCode.keySet(), outgoing, errors);

    // 找 START / END
    Set<String> startCodes = new HashSet<>();
    Set<String> endCodes = new HashSet<>();
    for (WorkflowNodeEntity n : byCode.values()) {
      if ("START".equalsIgnoreCase(n.getNodeType())) startCodes.add(n.getNodeCode());
      else if ("END".equalsIgnoreCase(n.getNodeType())) endCodes.add(n.getNodeCode());
    }

    // V11 START 不能有 incoming；END 不能有 outgoing
    for (String s : startCodes) {
      if (!incoming.getOrDefault(s, List.of()).isEmpty()) {
        errors.add(issue("V11", "START node has incoming edges", s));
      }
    }
    for (String e : endCodes) {
      if (!outgoing.getOrDefault(e, List.of()).isEmpty()) {
        errors.add(issue("V11", "END node has outgoing edges", e));
      }
    }

    // V2 不可达：从所有 START 出发 DFS 看哪些节点没被访问
    Set<String> reachableFromStart = new HashSet<>();
    for (String s : startCodes) dfs(s, outgoing, reachableFromStart);
    for (String code : byCode.keySet()) {
      if (!startCodes.isEmpty() && !reachableFromStart.contains(code)) {
        errors.add(issue("V2", "node unreachable from START", code));
      }
    }

    // V3 不可终止：从节点出发能否走到 END（反向 BFS 自 END）
    Set<String> reachableToEnd = new HashSet<>();
    for (String e : endCodes) dfs(e, incoming, reachableToEnd);
    for (String code : byCode.keySet()) {
      if (!endCodes.isEmpty() && !reachableToEnd.contains(code) && !endCodes.contains(code)) {
        errors.add(issue("V3", "node cannot reach END", code));
      }
    }

    // V4 DSL 引用 $.nodes.<X>.* 检查 X 存在
    for (WorkflowNodeEntity n : byCode.values()) {
      if (!Texts.hasText(n.getNodeParams())) continue;
      Matcher m = DSL_NODE_REF.matcher(n.getNodeParams());
      while (m.find()) {
        String referenced = m.group(1);
        if (!byCode.containsKey(referenced)) {
          errors.add(
              issue(
                  "V4", "node_params DSL references missing node: " + referenced, n.getNodeCode()));
        }
      }
    }

    // V6 / V7 跨日依赖
    for (WorkflowNodeEntity n : byCode.values()) {
      validateCrossDayDeps(n, errors);
    }

    // V9 / V10 GATEWAY join_mode 一致性
    for (WorkflowNodeEntity n : byCode.values()) {
      if (!"GATEWAY".equalsIgnoreCase(n.getNodeType())) continue;
      int incomingCount = incoming.getOrDefault(n.getNodeCode(), List.of()).size();
      JoinMode joinMode = parseJoinMode(n.getNodeParams());
      if (joinMode == null) continue;
      if (joinMode.allOf && incomingCount < 2) {
        errors.add(
            issue(
                "V9",
                "GATEWAY join_mode=ALL_OF requires ≥2 incoming, got " + incomingCount,
                n.getNodeCode()));
      }
      if (joinMode.nOfM != null) {
        int n1 = joinMode.nOfM[0];
        int m1 = joinMode.nOfM[1];
        if (n1 > m1 || m1 != incomingCount) {
          errors.add(
              issue(
                  "V10",
                  "GATEWAY join_mode=N_OF_M ("
                      + n1
                      + "/"
                      + m1
                      + ") inconsistent with incoming count "
                      + incomingCount,
                  n.getNodeCode()));
        }
      }
    }

    return WorkflowValidationResult.builder().errors(errors).warnings(warnings).build();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private Map<String, List<String>> buildAdjacency(
      List<WorkflowEdgeEntity> edges, boolean outgoingDirection) {
    Map<String, List<String>> adj = new HashMap<>();
    for (WorkflowEdgeEntity e : edges) {
      if (e == null || !Texts.hasText(e.getFromNodeCode()) || !Texts.hasText(e.getToNodeCode()))
        continue;
      String key = outgoingDirection ? e.getFromNodeCode() : e.getToNodeCode();
      String value = outgoingDirection ? e.getToNodeCode() : e.getFromNodeCode();
      adj.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return adj;
  }

  /** Tarjan-lite：DFS 三色标记找环。命中即记 ERROR。同时检 self-loop。 */
  private void detectCycles(
      Set<String> nodes, Map<String, List<String>> outgoing, List<ValidationIssue> errors) {
    // self-loop
    for (Map.Entry<String, List<String>> e : outgoing.entrySet()) {
      if (e.getValue() != null && e.getValue().contains(e.getKey())) {
        errors.add(issue("V1", "self-loop edge", e.getKey()));
      }
    }
    Map<String, Integer> color = new HashMap<>(); // 0=未访问 1=访问中 2=已完成
    for (String n : nodes) {
      if (color.getOrDefault(n, 0) == 0) {
        if (dfsHasCycle(n, outgoing, color, errors)) {
          // 已记录，继续扫其它子图
        }
      }
    }
  }

  private boolean dfsHasCycle(
      String node,
      Map<String, List<String>> outgoing,
      Map<String, Integer> color,
      List<ValidationIssue> errors) {
    color.put(node, 1);
    for (String next : outgoing.getOrDefault(node, List.of())) {
      Integer c = color.getOrDefault(next, 0);
      if (c == 1) {
        errors.add(issue("V1", "topology cycle through node " + node + " → " + next, node));
        return true;
      }
      if (c == 0 && dfsHasCycle(next, outgoing, color, errors)) {
        return true;
      }
    }
    color.put(node, 2);
    return false;
  }

  private void dfs(String node, Map<String, List<String>> adj, Set<String> visited) {
    if (!visited.add(node)) return;
    for (String next : adj.getOrDefault(node, List.of())) dfs(next, adj, visited);
  }

  private void validateCrossDayDeps(WorkflowNodeEntity node, List<ValidationIssue> errors) {
    String spec = node.getCrossDayDependencies();
    if (!Texts.hasText(spec)) return;
    List<CrossDayDependencySpec> deps;
    try {
      deps = JsonUtils.fromJson(spec, SPEC_LIST_TYPE);
    } catch (Exception parseFailure) {
      errors.add(
          issue(
              "V6",
              "cross_day_dependencies JSON parse failed: " + parseFailure.getMessage(),
              node.getNodeCode()));
      return;
    }
    if (deps == null) return;
    for (CrossDayDependencySpec dep : deps) {
      if (dep == null) continue;
      if (!Texts.hasText(dep.jobCode())) {
        errors.add(issue("V6", "cross_day_dep missing jobCode", node.getNodeCode()));
        continue;
      }
      if (Texts.hasText(dep.bizDateRange())) {
        Integer days = parseRangeDays(dep.bizDateRange());
        if (days != null && days > MAX_RANGE_DAYS) {
          errors.add(
              issue(
                  "V7",
                  "cross_day_dep range "
                      + dep.bizDateRange()
                      + " exceeds max "
                      + MAX_RANGE_DAYS
                      + " days",
                  node.getNodeCode()));
        }
      }
    }
  }

  private Integer parseRangeDays(String rangeTag) {
    String tag = rangeTag.trim().toUpperCase(Locale.ROOT);
    if (tag.startsWith("PREV_") && tag.endsWith("_BIZ_DAYS")) {
      return parseLeadingInt(tag.substring("PREV_".length()));
    }
    if (tag.startsWith("LAST_") && tag.endsWith("_WEEKS")) {
      Integer weeks = parseLeadingInt(tag.substring("LAST_".length()));
      return weeks == null ? null : weeks * 7;
    }
    return null;
  }

  private Integer parseLeadingInt(String text) {
    StringBuilder digits = new StringBuilder();
    for (char c : text.toCharArray()) {
      if (Character.isDigit(c)) digits.append(c);
      else break;
    }
    if (digits.isEmpty()) return null;
    try {
      return Integer.parseInt(digits.toString());
    } catch (NumberFormatException nfe) {
      return null;
    }
  }

  private JoinMode parseJoinMode(String nodeParams) {
    if (!Texts.hasText(nodeParams)) return null;
    try {
      Map<?, ?> map = JsonUtils.fromJson(nodeParams, Map.class);
      if (map == null) return null;
      Object jm = map.get("joinMode");
      if (jm == null) return null;
      String s = jm.toString().trim().toUpperCase(Locale.ROOT);
      if ("ALL_OF".equals(s)) return new JoinMode(true, null);
      if (s.matches("^\\d+_OF_\\d+$")) {
        String[] parts = s.split("_OF_");
        return new JoinMode(
            false, new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
      }
      return null;
    } catch (Exception ignore) {
      return null;
    }
  }

  private record JoinMode(boolean allOf, int[] nOfM) {}

  private ValidationIssue issue(String code, String message, String nodeCode) {
    return ValidationIssue.builder()
        .code(code)
        .severity(ValidationIssue.SEVERITY_ERROR)
        .nodeCode(nodeCode)
        .message(message)
        .build();
  }
}
