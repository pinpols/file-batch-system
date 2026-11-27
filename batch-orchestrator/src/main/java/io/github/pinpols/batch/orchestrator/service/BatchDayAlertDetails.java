package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 批次日告警详情共用的基础字段。 */
final class BatchDayAlertDetails {

    private BatchDayAlertDetails() {}

    static Map<String, Object> base(LaunchRequest request, String reasonCode) {
        return base(request, null, reasonCode);
    }

    static Map<String, Object> base(LaunchRequest request, TriggerRequestEntity triggerRequest, String reasonCode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tenantId", request.tenantId());
        detail.put("jobCode", request.jobCode());
        detail.put(
                "bizDate", request.bizDate() == null ? null : request.bizDate().toString());
        if (request.workflowDefinitionId() != null) {
            detail.put("workflowDefinitionId", request.workflowDefinitionId().toString());
        }
        if (triggerRequest != null && triggerRequest.getParentWorkflowRunId() != null) {
            detail.put("workflowRunId", triggerRequest.getParentWorkflowRunId().toString());
        }
        boolean requestHasParentWorkflowNode = triggerRequest != null
                && triggerRequest.getParentNodeCode() != null
                && !EmptyChecks.isBlank(Objects.requireNonNull(triggerRequest.getParentNodeCode()));
        if (requestHasParentWorkflowNode) {
            detail.put("nodeCode", triggerRequest.getParentNodeCode());
        }
        detail.put("requestId", request.requestId());
        detail.put(
                "triggerType",
                request.triggerType() == null ? null : request.triggerType().code());
        detail.put("reasonCode", reasonCode);
        return detail;
    }
}
