package io.github.pinpols.batch.orchestrator.application.engine;

import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 共享的完整冻结图和已上报范围夹具，供连续性单元测试及 SQL 测试使用。 */
final class CountContinuityFixtures {
  private CountContinuityFixtures() {}

  static String snapshot(
      String tenant, long definitionId, Map<String, Object> params, boolean edgeEnabled) {
    Map<String, Object> graph = new LinkedHashMap<>();
    graph.put("schemaVersion", 2);
    graph.put("workflowDefinitionId", definitionId);
    graph.put("workflowVersion", 1);
    graph.put(
        "nodes",
        List.of(
            node(tenant, definitionId, "IMPORT", Map.of()),
            node(tenant, definitionId, "PROCESS", params),
            node(tenant, definitionId, "CONTROL", Map.of())));
    graph.put(
        "edges",
        List.of(
            edge(tenant, definitionId, "IMPORT", edgeEnabled),
            edge(tenant, definitionId, "CONTROL", true)));
    graph.put(
        "jobDefinitions",
        Map.of(
            "IMPORT",
            job(tenant, "IMPORT"),
            "PROCESS",
            job(tenant, "PROCESS"),
            "CONTROL",
            job(tenant, "CONTROL")));
    return JsonUtils.toJson(graph);
  }

  static Map<String, Object> policy(int delta, String reason) {
    return Map.of(
        "countContinuity",
        Map.of(
            "enabled",
            true,
            "upstreamNodeCode",
            "IMPORT",
            "expectedDelta",
            delta,
            "deltaReason",
            reason));
  }

  static Map<String, Object> output(String direction, Object count, String scope, String unit) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(direction + "Count", count);
    if (scope != null)
      result.put(direction + "CountContext", Map.of("scopeId", scope, "unit", unit));
    return result;
  }

  static Map<String, Object> accountedInput(Object count, long excludedCount, String evidenceRef) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("inputCount", count);
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("scopeId", "version-1");
    context.put("unit", "RECORDS");
    context.put("excludedCount", excludedCount);
    context.put("exclusionEvidenceRef", evidenceRef);
    result.put("inputCountContext", context);
    return result;
  }

  private static Map<String, Object> node(
      String tenant, long definitionId, String code, Map<String, Object> params) {
    return Map.of(
        "nodeCode",
        code,
        "nodeType",
        "JOB",
        "relatedJobCode",
        code,
        "tenantId",
        tenant,
        "workflowDefinitionId",
        definitionId,
        "enabled",
        true,
        "nodeParams",
        JsonUtils.toJson(params));
  }

  private static Map<String, Object> edge(
      String tenant, long definitionId, String from, boolean enabled) {
    return Map.of(
        "tenantId",
        tenant,
        "workflowDefinitionId",
        definitionId,
        "fromNodeCode",
        from,
        "toNodeCode",
        "PROCESS",
        "enabled",
        enabled);
  }

  private static Map<String, Object> job(String tenant, String code) {
    return Map.of(
        "jobDefinitionId",
        100L,
        "jobCode",
        code,
        "jobType",
        "GENERAL",
        "jobDefinitionVersion",
        1,
        "executionDefinition",
        Map.of(
            "id", 100L, "tenantId", tenant, "jobCode", code, "jobType", "GENERAL", "version", 1));
  }
}
