package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.workflow.WorkflowValidationResult.ValidationIssue;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.workflow.CrossDayDependencySpec;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
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
  private static final Pattern DSL_OUTPUT_KEY_REF =
      Pattern.compile("\\$\\.nodes\\.([A-Za-z0-9_]+)\\.output\\.([A-Za-z0-9_]+)");
  private static final TypeReference<List<CrossDayDependencySpec>> SPEC_LIST_TYPE =
      new TypeReference<>() {};
  private static final int MAX_RANGE_DAYS = 90;

  /**
   * ADR-009 §Worker 暴露的 output key（按业务领域）— 内置 contract，避免依赖 worker SPI 上报。
   *
   * <p>未列业务（GENERAL / WORKFLOW）跳过 V5/V12 检查（ADR-025 §V5/V12 行规定向后兼容）。
   */
  private static final Map<String, Set<String>> KNOWN_OUTPUT_CONTRACT_BY_JOB_TYPE =
      Map.of(
          "IMPORT",
              Set.of(
                  "fileId",
                  "recordCount",
                  "parsedCount",
                  "validatedCount",
                  "skippedCount",
                  "bizDate"),
          "EXPORT",
              Set.of(
                  "fileId",
                  "objectName",
                  "recordCount",
                  "fileSizeBytes",
                  "checksumValue",
                  "bizDate"),
          "PROCESS",
              Set.of(
                  "processedCount",
                  "stagedCount",
                  "publishedCount",
                  "batchKey",
                  "highWaterMarkOut"),
          "DISPATCH",
              Set.of("fileId", "receiptCode", "receiptStatus", "externalRequestId", "channelCode"));

  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final JobDefinitionMapper jobDefinitionMapper;

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

    // V5 / V8 / V12 / V15
    Map<String, JobDefinitionEntity> jobDefByCode = loadJobDefinitions(byCode);
    Set<String> optionalNodes = collectOptionalNodes(byCode);
    for (WorkflowNodeEntity n : byCode.values()) {
      validateOutputContractRefs(n, byCode, jobDefByCode, optionalNodes, errors, warnings);
    }
    validateCalendarTimezoneConsistency(byCode.values(), jobDefByCode, warnings);

    return WorkflowValidationResult.builder().errors(errors).warnings(warnings).build();
  }

  /** 收集本 workflow 节点引用的 jobCode → JobDefinition 映射；缺失节点 / 缺失定义不抛错。 */
  private Map<String, JobDefinitionEntity> loadJobDefinitions(
      Map<String, WorkflowNodeEntity> nodes) {
    Map<String, JobDefinitionEntity> result = new HashMap<>();
    for (WorkflowNodeEntity node : nodes.values()) {
      String jobCode = node.getRelatedJobCode();
      if (!Texts.hasText(jobCode) || result.containsKey(jobCode)) {
        continue;
      }
      try {
        JobDefinitionEntity def =
            jobDefinitionMapper.selectFirstByTenantAndCodeAndEnabled(
                node.getTenantId(), jobCode, true);
        if (def != null) {
          result.put(jobCode, def);
        }
      } catch (RuntimeException ex) {
        log.warn(
            "validator skipped job_definition lookup for jobCode={} due to {}",
            jobCode,
            ex.getMessage());
      }
    }
    return result;
  }

  /**
   * ADR-025 V8：cross_day_dependencies 中任一 dep.scope=OPTIONAL 即视该节点本身为 OPTIONAL（输出可能 incomplete）。
   */
  private Set<String> collectOptionalNodes(Map<String, WorkflowNodeEntity> nodes) {
    Set<String> result = new HashSet<>();
    for (WorkflowNodeEntity node : nodes.values()) {
      String spec = node.getCrossDayDependencies();
      if (!Texts.hasText(spec)) continue;
      try {
        List<CrossDayDependencySpec> deps = JsonUtils.fromJson(spec, SPEC_LIST_TYPE);
        if (deps == null) continue;
        for (CrossDayDependencySpec dep : deps) {
          if (dep != null && "OPTIONAL".equalsIgnoreCase(dep.scope())) {
            result.add(node.getNodeCode());
            break;
          }
        }
      } catch (Exception ignored) {
        // V6 已经报过 parse 失败，这里不重复
      }
    }
    return result;
  }

  /**
   * V5 / V8 / V12 一次扫节点 nodeParams 的 DSL 输出引用：
   *
   * <ul>
   *   <li>V5 — Y 不在引用节点 jobType 内置 contract → WARN（jobType 未列 / 找不到 def 跳过）
   *   <li>V8 — X ∈ optionalNodes → ERROR（OPTIONAL → REQUIRED 传染性退化）
   *   <li>V12 — Y 命中 contract 时附加 type-hint WARN（contract type info 留 worker SPI 扩展位）
   * </ul>
   */
  private void validateOutputContractRefs(
      WorkflowNodeEntity node,
      Map<String, WorkflowNodeEntity> nodesByCode,
      Map<String, JobDefinitionEntity> jobDefByCode,
      Set<String> optionalNodes,
      List<ValidationIssue> errors,
      List<ValidationIssue> warnings) {
    if (!Texts.hasText(node.getNodeParams())) return;
    Matcher m = DSL_OUTPUT_KEY_REF.matcher(node.getNodeParams());
    while (m.find()) {
      String referencedNodeCode = m.group(1);
      String outputKey = m.group(2);
      // V8 — OPTIONAL 节点输出被引用
      if (optionalNodes.contains(referencedNodeCode)) {
        errors.add(
            issue(
                "V8",
                "node_params references output of OPTIONAL upstream node "
                    + referencedNodeCode
                    + " (cross-day OPTIONAL → REQUIRED contagion)",
                node.getNodeCode()));
      }
      WorkflowNodeEntity refNode = nodesByCode.get(referencedNodeCode);
      if (refNode == null) {
        // V4 已经报；这里跳过 V5/V12
        continue;
      }
      JobDefinitionEntity refJobDef =
          refNode.getRelatedJobCode() == null
              ? null
              : jobDefByCode.get(refNode.getRelatedJobCode());
      if (refJobDef == null) {
        // 未关联 job_definition → 跳过 V5/V12（向后兼容）
        continue;
      }
      Set<String> contract =
          KNOWN_OUTPUT_CONTRACT_BY_JOB_TYPE.get(
              refJobDef.jobType() == null ? "" : refJobDef.jobType().toUpperCase(Locale.ROOT));
      if (contract == null) {
        // jobType 不在内置 contract（如 GENERAL / WORKFLOW）→ 跳过
        continue;
      }
      // V5
      if (!contract.contains(outputKey)) {
        warnings.add(
            warning(
                "V5",
                "DSL ref $.nodes."
                    + referencedNodeCode
                    + ".output."
                    + outputKey
                    + " not in known output contract for jobType="
                    + refJobDef.jobType(),
                node.getNodeCode()));
      } else {
        // V12 占位 — contract type info 留 worker SPI 扩展（@WorkerOutputContract 注解未来注入）；
        // 当前只对 contract-hit 输出 key 记一条信息级 hint，避免 type 误判
        warnings.add(
            warning(
                "V12",
                "DSL ref $.nodes."
                    + referencedNodeCode
                    + ".output."
                    + outputKey
                    + " hits contract; type-check pending worker SPI",
                node.getNodeCode()));
      }
    }
  }

  /** V15 — 同 workflow 多个节点引用的 job_definition.calendarCode / timezone 不一致 → WARN。 */
  private void validateCalendarTimezoneConsistency(
      java.util.Collection<WorkflowNodeEntity> nodes,
      Map<String, JobDefinitionEntity> jobDefByCode,
      List<ValidationIssue> warnings) {
    Set<String> calendars = new HashSet<>();
    Set<String> timezones = new HashSet<>();
    for (WorkflowNodeEntity node : nodes) {
      if (!Texts.hasText(node.getRelatedJobCode())) continue;
      JobDefinitionEntity def = jobDefByCode.get(node.getRelatedJobCode());
      if (def == null) continue;
      if (Texts.hasText(def.calendarCode())) calendars.add(def.calendarCode());
      if (Texts.hasText(def.timezone())) timezones.add(def.timezone());
    }
    if (calendars.size() > 1) {
      warnings.add(
          warning(
              "V15", "workflow nodes reference jobs with mixed calendarCode: " + calendars, null));
    }
    if (timezones.size() > 1) {
      warnings.add(
          warning("V15", "workflow nodes reference jobs with mixed timezone: " + timezones, null));
    }
  }

  private ValidationIssue warning(String code, String message, String nodeCode) {
    return ValidationIssue.builder()
        .code(code)
        .severity(ValidationIssue.SEVERITY_WARN)
        .nodeCode(nodeCode)
        .message(message)
        .build();
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
