package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 身份与事务测试使用的完整最小控制图；不替代真实 JOB 图的容量恢复集成场景。 */
public final class WorkflowLaunchWaitFixtures {
  private WorkflowLaunchWaitFixtures() {}

  public static WorkflowLaunchWaitSnapshot snapshot(LaunchRequest request) {
    WorkflowNodeEntity start = node(request, "START", "START");
    WorkflowNodeEntity end = node(request, "END", "END");
    Map<String, Object> execution = new LinkedHashMap<>();
    execution.put("schemaVersion", 2);
    execution.put("workflowDefinitionId", request.workflowDefinitionId());
    execution.put("workflowVersion", 1);
    execution.put("workflowCalendarCode", "CAL_WAIT");
    execution.put("triggerRequestId", request.requestId());
    execution.put("nodes", List.of(start, end));
    execution.put("edges", List.of());
    execution.put("jobDefinitions", Map.of());
    execution.put("initialNodes", List.of(Map.of("nodeCode", "END", "nodeType", "END")));
    WorkflowRunEntity prepared = new WorkflowRunEntity();
    prepared.setWorkflowVersionSnapshot(1);
    prepared.setTraceId("wait-fixture-trace");
    prepared.setExecutionSnapshot(JsonUtils.toJson(execution));
    return WorkflowLaunchWaitSnapshot.capture(request, request, prepared);
  }

  private static WorkflowNodeEntity node(LaunchRequest request, String code, String type) {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setTenantId(request.tenantId());
    node.setWorkflowDefinitionId(request.workflowDefinitionId());
    node.setNodeCode(code);
    node.setNodeType(type);
    node.setEnabled(true);
    return node;
  }
}
