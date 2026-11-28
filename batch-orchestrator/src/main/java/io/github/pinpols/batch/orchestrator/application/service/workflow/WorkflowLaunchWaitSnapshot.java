package io.github.pinpols.batch.orchestrator.application.service.workflow;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.common.utils.JsonUtils;

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
        Map<String, Object> params = effective.params() == null ? Map.of() : effective.params();
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
        Map<String, Object> params = JsonUtils.fromJson(launchParamsJson, new TypeReference<Map<String, Object>>() {});
        if (params == null) {
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
        boolean snapshotFieldsAreInvalid = EmptyValues.isBlank(originalRequestJson)
                || EmptyValues.isBlank(launchParamsJson)
                || workflowVersion == null
                || workflowVersion <= 0
                || EmptyValues.isBlank(traceId)
                || EmptyValues.isBlank(executionSnapshotJson);
        if (snapshotFieldsAreInvalid) {
            throw invalidSnapshot();
        }
        LaunchRequest original = originalRequest();
        boolean originalRequestIsInvalid = original == null
                || original.workflowDefinitionId() == null
                || original.workflowDefinitionId() <= 0
                || original.jobCode() != null
                || EmptyValues.isBlank(original.tenantId())
                || EmptyValues.isBlank(original.requestId())
                || original.bizDate() == null;
        if (originalRequestIsInvalid) {
            throw invalidSnapshot();
        }
        WorkflowRunExecutionSnapshot parsed = executionSnapshot();
        if (parsed == null || EmptyValues.isBlank(parsed.workflowCalendarCode())) {
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
        if (request == null) {
            throw invalidSnapshot();
        }
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
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
