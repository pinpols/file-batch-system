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
        nullToEmpty(workflowNodeMapper.selectByWorkflowDefinitionId(workflowDefinitionId));
    List<WorkflowEdgeEntity> edges =
        nullToEmpty(workflowEdgeMapper.selectAllByWorkflowDefinitionId(workflowDefinitionId));

    List<ValidationIssue> errors = new ArrayList<>();
    List<ValidationIssue> warnings = new ArrayList<>();

    Map<String, WorkflowNodeEntity> byCode = indexNodesByCode(nodes, errors);
    validateEdgeNodeRefs(edges, byCode, errors);

    Map<String, List<String>> outgoing = buildAdjacency(edges, true);
    Map<String, List<String>> incoming = buildAdjacency(edges, false);

    detectCycles(byCode.keySet(), outgoing, errors);

    Set<String> startCodes = new HashSet<>();
    Set<String> endCodes = new HashSet<>();
    for (WorkflowNodeEntity n : byCode.values()) {
      if ("START".equalsIgnoreCase(n.getNodeType())) startCodes.add(n.getNodeCode());
      else if ("END".equalsIgnoreCase(n.getNodeType())) endCodes.add(n.getNodeCode());
    }

    validateStartEndEdges(startCodes, endCodes, incoming, outgoing, errors);
    validateReachability(byCode.keySet(), startCodes, endCodes, outgoing, incoming, errors);
    validateDslNodeRefs(byCode, errors);

    for (WorkflowNodeEntity n : byCode.values()) {
      validateCrossDayDeps(n, errors);
      validateSensorSpec(n, errors);
    }

    validateGatewayJoinMode(byCode.values(), incoming, errors);

    Map<String, JobDefinitionEntity> jobDefByCode = loadJobDefinitions(byCode);
    Set<String> optionalNodes = collectOptionalNodes(byCode);
    for (WorkflowNodeEntity n : byCode.values()) {
      validateOutputContractRefs(n, byCode, jobDefByCode, optionalNodes, errors, warnings);
    }
    validateCalendarTimezoneConsistency(byCode.values(), jobDefByCode, warnings);

    return WorkflowValidationResult.builder().errors(errors).warnings(warnings).build();
  }

  private static <T> List<T> nullToEmpty(List<T> list) {
    return list == null ? List.of() : list;
  }

  private Map<String, WorkflowNodeEntity> indexNodesByCode(
      List<WorkflowNodeEntity> nodes, List<ValidationIssue> errors) {
    Map<String, WorkflowNodeEntity> byCode = new LinkedHashMap<>();
    Set<String> duplicates = new HashSet<>();
    for (WorkflowNodeEntity n : nodes) {
      if (n == null || !Texts.hasText(n.getNodeCode())) continue;
      WorkflowNodeEntity prior = byCode.put(n.getNodeCode(), n);
      if (prior != null) duplicates.add(n.getNodeCode());
    }
    for (String code : duplicates) {
      errors.add(issue("V13", "duplicate node_code", code));
    }
    return byCode;
  }

  private void validateEdgeNodeRefs(
      List<WorkflowEdgeEntity> edges,
      Map<String, WorkflowNodeEntity> byCode,
      List<ValidationIssue> errors) {
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
  }

  private void validateStartEndEdges(
      Set<String> startCodes,
      Set<String> endCodes,
      Map<String, List<String>> incoming,
      Map<String, List<String>> outgoing,
      List<ValidationIssue> errors) {
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
  }

  private void validateReachability(
      Set<String> allCodes,
      Set<String> startCodes,
      Set<String> endCodes,
      Map<String, List<String>> outgoing,
      Map<String, List<String>> incoming,
      List<ValidationIssue> errors) {
    Set<String> reachableFromStart = new HashSet<>();
    for (String s : startCodes) dfs(s, outgoing, reachableFromStart);
    for (String code : allCodes) {
      if (!startCodes.isEmpty() && !reachableFromStart.contains(code)) {
        errors.add(issue("V2", "node unreachable from START", code));
      }
    }
    Set<String> reachableToEnd = new HashSet<>();
    for (String e : endCodes) dfs(e, incoming, reachableToEnd);
    for (String code : allCodes) {
      if (!endCodes.isEmpty() && !reachableToEnd.contains(code) && !endCodes.contains(code)) {
        errors.add(issue("V3", "node cannot reach END", code));
      }
    }
  }

  private void validateDslNodeRefs(
      Map<String, WorkflowNodeEntity> byCode, List<ValidationIssue> errors) {
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
  }

  private void validateGatewayJoinMode(
      java.util.Collection<WorkflowNodeEntity> nodes,
      Map<String, List<String>> incoming,
      List<ValidationIssue> errors) {
    for (WorkflowNodeEntity n : nodes) {
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

  private static final TypeReference<Map<String, Object>> SENSOR_MAP_TYPE =
      new TypeReference<>() {};

  private static final Set<String> SENSOR_TYPES =
      Set.of("FILE_ARRIVAL", "HTTP_POLL", "KAFKA_OFFSET", "DB_ROW_EXISTS");

  private static final Set<String> SENSOR_TIMEOUT_ACTIONS = Set.of("FAIL", "SKIP_DOWNSTREAM");

  /**
   * ADR-028 V16：WAIT 节点的 sensor_spec / sensor_type / 超时配置静态校验。非 WAIT 节点直接返回。
   *
   * <ul>
   *   <li>V16-a sensor_type 必填
   *   <li>V16-b sensor_type 必须在 4 个 enum 之一
   *   <li>V16-c sensor_spec 必填 + 类型必须是 object；按 type 校验 spec 关键字段
   *   <li>V16-d timeout_seconds &gt; poll_interval_seconds（两者均必填且 &gt;0）
   *   <li>V16-e on_timeout 必填且在 FAIL/SKIP_DOWNSTREAM 之一
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private void validateSensorSpec(WorkflowNodeEntity node, List<ValidationIssue> errors) {
    if (!"WAIT".equalsIgnoreCase(node.getNodeType())) {
      return;
    }
    Map<String, Object> params;
    try {
      params =
          Texts.hasText(node.getNodeParams())
              ? JsonUtils.fromJson(node.getNodeParams(), SENSOR_MAP_TYPE)
              : Map.of();
    } catch (Exception e) {
      errors.add(
          issue(
              "V16", "WAIT node_params JSON parse failed: " + e.getMessage(), node.getNodeCode()));
      return;
    }
    if (params == null) params = Map.of();

    String sensorType = asString(params.get("sensor_type"));
    if (!Texts.hasText(sensorType)) {
      errors.add(issue("V16-a", "WAIT node missing sensor_type", node.getNodeCode()));
      return;
    }
    if (!SENSOR_TYPES.contains(sensorType)) {
      errors.add(
          issue(
              "V16-b",
              "WAIT sensor_type invalid: " + sensorType + " (allowed: " + SENSOR_TYPES + ")",
              node.getNodeCode()));
      return;
    }

    Object spec = params.get("sensor_spec");
    if (!(spec instanceof Map)) {
      errors.add(issue("V16-c", "WAIT sensor_spec missing or not an object", node.getNodeCode()));
    } else {
      validateSensorSpecByType(sensorType, (Map<String, Object>) spec, node.getNodeCode(), errors);
    }

    Long timeout = asLong(params.get("timeout_seconds"));
    Long pollInterval = asLong(params.get("poll_interval_seconds"));
    if (timeout == null || timeout <= 0) {
      errors.add(issue("V16-d", "WAIT timeout_seconds missing or <=0", node.getNodeCode()));
    }
    if (pollInterval == null || pollInterval <= 0) {
      errors.add(issue("V16-d", "WAIT poll_interval_seconds missing or <=0", node.getNodeCode()));
    }
    if (timeout != null && pollInterval != null && timeout <= pollInterval) {
      errors.add(
          issue(
              "V16-d",
              "WAIT timeout_seconds must be greater than poll_interval_seconds",
              node.getNodeCode()));
    }

    String onTimeout = asString(params.get("on_timeout"));
    if (!Texts.hasText(onTimeout)) {
      errors.add(issue("V16-e", "WAIT on_timeout missing", node.getNodeCode()));
    } else if (!SENSOR_TIMEOUT_ACTIONS.contains(onTimeout)) {
      errors.add(
          issue(
              "V16-e",
              "WAIT on_timeout invalid: "
                  + onTimeout
                  + " (allowed: "
                  + SENSOR_TIMEOUT_ACTIONS
                  + ")",
              node.getNodeCode()));
    }
  }

  private void validateSensorSpecByType(
      String sensorType, Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors) {
    switch (sensorType) {
      case "FILE_ARRIVAL" -> {
        if (!Texts.hasText(asString(spec.get("pattern")))) {
          errors.add(issue("V16-c", "FILE_ARRIVAL sensor_spec.pattern required", nodeCode));
        }
        Long age = asLong(spec.get("maxAgeSeconds"));
        if (age == null || age <= 0) {
          errors.add(issue("V16-c", "FILE_ARRIVAL sensor_spec.maxAgeSeconds required", nodeCode));
        }
      }
      case "HTTP_POLL" -> {
        if (!Texts.hasText(asString(spec.get("url")))) {
          errors.add(issue("V16-c", "HTTP_POLL sensor_spec.url required", nodeCode));
        }
        if (!Texts.hasText(asString(spec.get("matchExpr")))) {
          errors.add(issue("V16-c", "HTTP_POLL sensor_spec.matchExpr required", nodeCode));
        }
      }
      case "KAFKA_OFFSET" -> {
        if (!Texts.hasText(asString(spec.get("topic")))) {
          errors.add(issue("V16-c", "KAFKA_OFFSET sensor_spec.topic required", nodeCode));
        }
        if (asLong(spec.get("partition")) == null) {
          errors.add(issue("V16-c", "KAFKA_OFFSET sensor_spec.partition required", nodeCode));
        }
        if (asLong(spec.get("minOffset")) == null) {
          errors.add(issue("V16-c", "KAFKA_OFFSET sensor_spec.minOffset required", nodeCode));
        }
      }
      case "DB_ROW_EXISTS" -> {
        if (!Texts.hasText(asString(spec.get("schema")))) {
          errors.add(issue("V16-c", "DB_ROW_EXISTS sensor_spec.schema required", nodeCode));
        }
        if (!Texts.hasText(asString(spec.get("sql")))) {
          errors.add(issue("V16-c", "DB_ROW_EXISTS sensor_spec.sql required", nodeCode));
        }
      }
      default -> {
        // 不应到此（type 已在外层 V16-b 校验）
      }
    }
  }

  private static String asString(Object v) {
    return v == null ? null : v.toString();
  }

  private static Long asLong(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(v.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
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
