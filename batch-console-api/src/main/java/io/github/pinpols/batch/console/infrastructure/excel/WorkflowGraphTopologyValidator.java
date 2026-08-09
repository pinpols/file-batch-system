package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONDITION_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EDGE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_FROM_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TO_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.KEY_SEP_COLON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WF_EDGE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WF_NODE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.excelRowNo;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.normalize;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.normalizeEnum;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-025 §校验项清单 — Excel 导入阶段的纯静态 DAG 拓扑校验。
 *
 * <p>覆盖:V1 环/自环,V2 不可从 START 到达,V3 不可到达 END,V4 nodeParams DSL 引用了不存在的节点, V11 START 唯一/END
 * ≥1/START 无入边/END 无出边,V17 CONDITION 边必填 conditionExpr,V18 DSL 只能引用拓扑序前节点。
 *
 * <p>不覆盖(留给 enable 时 orchestrator WorkflowGraphValidator):V5/V8/V12 output contract、 V9/V10
 * GATEWAY join_mode、V15 calendar/timezone 一致性、V16 WAIT sensor_spec —— Excel 校验阶段 只拦致命拓扑问题,深 JSON
 * spec 解析放在 enable 路径降低 import 复杂度。
 */
final class WorkflowGraphTopologyValidator {

  private WorkflowGraphTopologyValidator() {}

  static List<WorkbookIssue> validate(
      List<Map<String, String>> validWfNodes, List<Map<String, String>> validWfEdges) {
    Map<String, WfGraphCtx> graphs = new LinkedHashMap<>();
    for (Map<String, String> n : validWfNodes) {
      String key = wfTopoKey(n);
      graphs.computeIfAbsent(key, k -> new WfGraphCtx()).nodes.add(n);
    }
    for (Map<String, String> e : validWfEdges) {
      String key = wfTopoKey(e);
      WfGraphCtx g = graphs.get(key);
      if (g != null) {
        g.edges.add(e);
      }
      // 找不到 workflow 的 edge 已在 validateWfEdgeRow 中以 FK 失败标出,这里不重复
    }
    List<WorkbookIssue> issues = new ArrayList<>();
    for (WfGraphCtx g : graphs.values()) {
      validateWorkflowGraph(g, issues);
    }
    return issues;
  }

  private static String wfTopoKey(Map<String, String> row) {
    String v = Texts.hasText(row.get(COL_WORKFLOW_VERSION))
        ? normalize(row.get(COL_WORKFLOW_VERSION))
        : normalize(row.get(COL_VERSION));
    return normalize(row.get(COL_WORKFLOW_CODE)) + KEY_SEP_COLON + v;
  }

  private static final Pattern DSL_NODE_REF = Pattern.compile("\\$\\.nodes\\.(\\w+)\\.");

  // workflow graph 校验是一个完整 transaction:索引节点 -> 邻接表 -> 环/可达/终止 一气呵成,
  // 拆方法会让中间状态(byCode/incoming/outgoing/startCodes/endCodes)散落到字段,牺牲可读性。
  @SuppressWarnings("PMD.NcssCount")
  private static void validateWorkflowGraph(WfGraphCtx g, List<WorkbookIssue> issues) {
    // 索引:nodeCode -> row(取首个,duplicate 已在 validateWfNodeRow 报过)
    Map<String, Map<String, String>> byCode = new LinkedHashMap<>();
    for (Map<String, String> n : g.nodes) {
      String code = normalize(n.get(COL_NODE_CODE));
      if (Texts.hasText(code)) {
        byCode.putIfAbsent(code, n);
      }
    }
    // 邻接表
    Map<String, List<String>> outgoing = new HashMap<>();
    Map<String, List<String>> incoming = new HashMap<>();
    for (Map<String, String> e : g.edges) {
      String f = normalize(e.get(COL_FROM_NODE_CODE));
      String t = normalize(e.get(COL_TO_NODE_CODE));
      if (!Texts.hasText(f) || !Texts.hasText(t)) {
        continue;
      }
      outgoing.computeIfAbsent(f, k -> new ArrayList<>()).add(t);
      incoming.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
    }
    // START / END 集合
    Set<String> startCodes = new LinkedHashSet<>();
    Set<String> endCodes = new LinkedHashSet<>();
    for (Map.Entry<String, Map<String, String>> e : byCode.entrySet()) {
      String type = normalizeEnum(e.getValue().get(COL_NODE_TYPE));
      if ("START".equals(type)) {
        startCodes.add(e.getKey());
      } else if ("END".equals(type)) {
        endCodes.add(e.getKey());
      }
    }
    int fallbackRowNo = 2;
    // V11 — START 数量、END 数量、START 入边、END 出边
    if (EmptyChecks.isEmpty(startCodes) && EmptyChecks.isNotEmpty(byCode)) {
      // 没有 START 节点,挑首节点定位
      Map<String, String> first = byCode.values().iterator().next();
      issues.add(new WorkbookIssue(
          WF_NODE_SHEET,
          excelRowNo(first, fallbackRowNo),
          COL_NODE_TYPE,
          "workflow graph missing START node"));
    }
    if (EmptyChecks.isEmpty(endCodes) && EmptyChecks.isNotEmpty(byCode)) {
      Map<String, String> first = byCode.values().iterator().next();
      issues.add(new WorkbookIssue(
          WF_NODE_SHEET,
          excelRowNo(first, fallbackRowNo),
          COL_NODE_TYPE,
          "workflow graph requires at least 1 END node"));
    }
    if (startCodes.size() > 1) {
      for (String s : startCodes) {
        Map<String, String> n = byCode.get(s);
        issues.add(new WorkbookIssue(
            WF_NODE_SHEET,
            excelRowNo(n, fallbackRowNo),
            COL_NODE_TYPE,
            "workflow graph has multiple START nodes: " + startCodes));
      }
    }
    for (String s : startCodes) {
      if (EmptyChecks.isNotEmpty(incoming.getOrDefault(s, List.of()))) {
        Map<String, String> n = byCode.get(s);
        issues.add(new WorkbookIssue(
            WF_NODE_SHEET,
            excelRowNo(n, fallbackRowNo),
            COL_NODE_TYPE,
            "START node cannot have incoming edges: " + s));
      }
    }
    for (String e : endCodes) {
      if (EmptyChecks.isNotEmpty(outgoing.getOrDefault(e, List.of()))) {
        Map<String, String> n = byCode.get(e);
        issues.add(new WorkbookIssue(
            WF_NODE_SHEET,
            excelRowNo(n, fallbackRowNo),
            COL_NODE_TYPE,
            "END node cannot have outgoing edges: " + e));
      }
    }
    // V1 — self-loop + cycle
    int fallbackEdgeRow = 2;
    for (Map<String, String> e : g.edges) {
      String f = normalize(e.get(COL_FROM_NODE_CODE));
      String t = normalize(e.get(COL_TO_NODE_CODE));
      if (Texts.hasText(f) && f.equals(t)) {
        issues.add(new WorkbookIssue(
            WF_EDGE_SHEET,
            excelRowNo(e, fallbackEdgeRow),
            COL_TO_NODE_CODE,
            "self-loop edge on node: " + f));
      }
      fallbackEdgeRow++;
    }
    Map<String, Integer> color = new HashMap<>();
    for (String n : byCode.keySet()) {
      if (color.getOrDefault(n, 0) == 0) {
        dfsCycle(n, outgoing, color, byCode, fallbackRowNo, issues);
      }
    }
    // V2 — unreachable from START
    Set<String> reachFromStart = new HashSet<>();
    for (String s : startCodes) {
      dfsCollect(s, outgoing, reachFromStart);
    }
    if (EmptyChecks.isNotEmpty(startCodes)) {
      for (String code : byCode.keySet()) {
        if (!reachFromStart.contains(code)) {
          Map<String, String> n = byCode.get(code);
          issues.add(new WorkbookIssue(
              WF_NODE_SHEET,
              excelRowNo(n, fallbackRowNo),
              COL_NODE_CODE,
              "node unreachable from START: " + code));
        }
      }
    }
    // V3 — cannot reach END
    Set<String> reachToEnd = new HashSet<>();
    for (String e : endCodes) {
      dfsCollect(e, incoming, reachToEnd);
    }
    if (EmptyChecks.isNotEmpty(endCodes)) {
      for (String code : byCode.keySet()) {
        if (!reachToEnd.contains(code) && !endCodes.contains(code)) {
          Map<String, String> n = byCode.get(code);
          issues.add(new WorkbookIssue(
              WF_NODE_SHEET,
              excelRowNo(n, fallbackRowNo),
              COL_NODE_CODE,
              "node cannot reach any END: " + code));
        }
      }
    }
    // V17 — CONDITION edge 必填 conditionExpr
    int rowNo = 2;
    for (Map<String, String> e : g.edges) {
      String type = normalizeEnum(e.get(COL_EDGE_TYPE));
      String expr = normalize(e.get(COL_CONDITION_EXPR));
      if ("CONDITION".equals(type) && !Texts.hasText(expr)) {
        issues.add(new WorkbookIssue(
            WF_EDGE_SHEET,
            excelRowNo(e, rowNo),
            COL_CONDITION_EXPR,
            "CONDITION edge requires non-empty condition_expr"));
      }
      rowNo++;
    }
    // V4 / V18 — DSL refs 必须存在 & 必须是拓扑序前(ancestor)
    Map<String, Set<String>> ancestors = computeAncestors(byCode.keySet(), outgoing, startCodes);
    for (Map.Entry<String, Map<String, String>> e : byCode.entrySet()) {
      Map<String, String> n = e.getValue();
      String params = n.get(COL_NODE_PARAMS);
      if (!Texts.hasText(params)) {
        continue;
      }
      Matcher m = DSL_NODE_REF.matcher(params);
      while (m.find()) {
        String ref = m.group(1);
        if (!byCode.containsKey(ref)) {
          issues.add(new WorkbookIssue(
              WF_NODE_SHEET,
              excelRowNo(n, fallbackRowNo),
              COL_NODE_PARAMS,
              "node_params DSL references missing node: " + ref));
          continue;
        }
        Set<String> ancSet = ancestors.getOrDefault(e.getKey(), Set.of());
        if (!ancSet.contains(ref)) {
          issues.add(new WorkbookIssue(
              WF_NODE_SHEET,
              excelRowNo(n, fallbackRowNo),
              COL_NODE_PARAMS,
              "node_params DSL can only reference upstream nodes; '"
                  + ref
                  + "' is not an ancestor of '"
                  + e.getKey()
                  + "'"));
        }
      }
    }
  }

  private static void dfsCycle(
      String node,
      Map<String, List<String>> outgoing,
      Map<String, Integer> color,
      Map<String, Map<String, String>> byCode,
      int fallback,
      List<WorkbookIssue> issues) {
    color.put(node, 1);
    for (String next : outgoing.getOrDefault(node, List.of())) {
      Integer c = color.getOrDefault(next, 0);
      if (c == 1) {
        Map<String, String> n = byCode.get(node);
        issues.add(new WorkbookIssue(
            WF_EDGE_SHEET,
            n == null ? fallback : excelRowNo(n, fallback),
            COL_TO_NODE_CODE,
            "topology cycle detected through edge: " + node + " -> " + next));
        return;
      }
      if (c == 0) {
        dfsCycle(next, outgoing, color, byCode, fallback, issues);
      }
    }
    color.put(node, 2);
  }

  private static void dfsCollect(String node, Map<String, List<String>> adj, Set<String> visited) {
    if (!visited.add(node)) {
      return;
    }
    for (String next : adj.getOrDefault(node, List.of())) {
      dfsCollect(next, adj, visited);
    }
  }

  /** 给每个节点算出可达的祖先集合。从每个 START 出发,沿 outgoing 边游走,把 source + source 的祖先并入 target 的祖先。 */
  private static Map<String, Set<String>> computeAncestors(
      Set<String> allCodes, Map<String, List<String>> outgoing, Set<String> startCodes) {
    Map<String, Set<String>> ancestors = new HashMap<>();
    for (String code : allCodes) {
      ancestors.put(code, new HashSet<>());
    }
    for (String s : startCodes) {
      walkAncestors(s, outgoing, ancestors, new HashSet<>());
    }
    return ancestors;
  }

  private static void walkAncestors(
      String node,
      Map<String, List<String>> outgoing,
      Map<String, Set<String>> ancestors,
      Set<String> visited) {
    if (!visited.add(node)) {
      return;
    }
    for (String next : outgoing.getOrDefault(node, List.of())) {
      Set<String> nextAnc = ancestors.get(next);
      if (nextAnc != null) {
        nextAnc.add(node);
        Set<String> nodeAnc = ancestors.get(node);
        if (nodeAnc != null) {
          nextAnc.addAll(nodeAnc);
        }
      }
      walkAncestors(next, outgoing, ancestors, visited);
    }
  }

  private static final class WfGraphCtx {
    final List<Map<String, String>> nodes = new ArrayList<>();
    final List<Map<String, String>> edges = new ArrayList<>();
  }
}
