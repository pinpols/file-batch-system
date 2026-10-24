package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Shared base fields for batch-day alert details. */
final class BatchDayAlertDetails {

  private BatchDayAlertDetails() {}

  static Map<String, Object> base(LaunchRequest request, String reasonCode) {
    return base(request, null, reasonCode);
  }

  static Map<String, Object> base(
      LaunchRequest request, TriggerRequestEntity triggerRequest, String reasonCode) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tenantId", request.tenantId());
    detail.put("jobCode", request.jobCode());
    detail.put("bizDate", request.bizDate() == null ? null : request.bizDate().toString());
    if (EmptyChecks.isNotNull(request.workflowDefinitionId())) {
      detail.put("workflowDefinitionId", request.workflowDefinitionId().toString());
    }
    if (EmptyChecks.isNotNull(triggerRequest) && EmptyChecks.isNotNull(triggerRequest.getParentWorkflowRunId())) {
      detail.put("workflowRunId", triggerRequest.getParentWorkflowRunId().toString());
    }
    boolean requestHasParentWorkflowNode = EmptyChecks.isNotNull(triggerRequest)
        && EmptyChecks.isNotNull(triggerRequest.getParentNodeCode())
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
