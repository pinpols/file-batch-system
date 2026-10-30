package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.Map;
import java.util.Objects;

/** 容量等待持有已构建的同一执行快照；该对象只在受信运行时内解封使用。 */
public record WorkflowLaunchWaitSnapshot(
    String originalRequestJson,
    String launchParamsJson,
    Integer workflowVersion,
    Long singleJobDefinitionId,
    String traceId,
    String executionSnapshotJson) {

  public static WorkflowLaunchWaitSnapshot capture(
      LaunchRequest original, LaunchRequest effective, WorkflowRunEntity prepared) {
    Map<String, Object> params =
        EmptyChecks.isNull(effective.params()) ? Map.of() : effective.params();
    String originalJson = JsonUtils.toJson(original);
    String paramsJson = JsonUtils.toJson(params);
    WorkflowLaunchWaitSnapshot snapshot = new WorkflowLaunchWaitSnapshot(
        originalJson,
        paramsJson,
        prepared.getWorkflowVersionSnapshot(),
        prepared.getSingleJobDefinitionId(),
        prepared.getTraceId(),
        prepared.getExecutionSnapshot());
    return snapshot;
  }

  public LaunchRequest originalRequest() {
    return JsonUtils.fromJsonStrict(originalRequestJson, LaunchRequest.class);
  }

  public LaunchRequest effectiveRequest() {
    Map<String, Object> params =
        JsonUtils.fromJson(launchParamsJson, new TypeReference<Map<String, Object>>() {});
    if (EmptyChecks.isNull(params)) {
      throw invalidSnapshot();
    }
    LaunchRequest original = originalRequest();
    return original.toBuilder().params(params).build();
  }

  public void requireSameRequest(LaunchRequest incoming) {
    LaunchRequest original = originalRequest();
    Map<String, Object> expected = comparableRequest(original);
    Map<String, Object> actual = comparableRequest(incoming);
    if (!expected.equals(actual)) {
      throw invalidSnapshot();
    }
  }

  public void validate() {
    if (EmptyChecks.isBlank(originalRequestJson)
        || EmptyChecks.isBlank(launchParamsJson)
        || EmptyChecks.isNull(workflowVersion)
        || workflowVersion <= 0
        || EmptyChecks.isBlank(traceId)
        || EmptyChecks.isBlank(executionSnapshotJson)) {
      throw invalidSnapshot();
    }
    LaunchRequest original = originalRequest();
    if (EmptyChecks.isNull(original)
        || EmptyChecks.isNull(original.workflowDefinitionId())
        || original.workflowDefinitionId() <= 0
        || EmptyChecks.isNotNull(original.jobCode())
        || EmptyChecks.isBlank(original.tenantId())
        || EmptyChecks.isBlank(original.requestId())
        || EmptyChecks.isNull(original.bizDate())) {
      throw invalidSnapshot();
    }
    WorkflowRunExecutionSnapshot parsed = executionSnapshot();
    if (EmptyChecks.isNull(parsed) || EmptyChecks.isBlank(parsed.workflowCalendarCode())) {
      throw invalidSnapshot();
    }
    Map<String, Object> raw =
        JsonUtils.fromJson(executionSnapshotJson, new TypeReference<Map<String, Object>>() {});
    if (!Objects.equals(raw.get("triggerRequestId"), original.requestId())) {
      throw invalidSnapshot();
    }
    effectiveRequest();
  }

  public Long replayOperationId() {
    return executionSnapshot().replayOperationId();
  }

  private WorkflowRunExecutionSnapshot executionSnapshot() {
    LaunchRequest original = originalRequest();
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId(original.tenantId());
    run.setWorkflowDefinitionId(original.workflowDefinitionId());
    run.setWorkflowVersionSnapshot(workflowVersion);
    run.setExecutionSnapshot(executionSnapshotJson);
    return WorkflowRunExecutionSnapshot.from(run);
  }

  public static BizException invalidSnapshot() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
  }

  private static Map<String, Object> comparableRequest(LaunchRequest request) {
    if (EmptyChecks.isNull(request)) {
      throw invalidSnapshot();
    }
    Map<String, Object> params = EmptyChecks.isNull(request.params()) ? Map.of() : request.params();
    LaunchRequest normalized = request.toBuilder()
        .params(params)
        .dryRun(Boolean.TRUE.equals(request.dryRun()))
        .traceId(null)
        .build();
    String serialized = JsonUtils.toJson(normalized);
    return JsonUtils.fromJson(serialized, new TypeReference<Map<String, Object>>() {});
  }

  @Override
  public String toString() {
    return "WorkflowLaunchWaitSnapshot[workflowVersion=" + workflowVersion + "]";
  }
}
