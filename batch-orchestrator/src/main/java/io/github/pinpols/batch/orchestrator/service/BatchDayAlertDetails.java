package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared base fields for batch-day alert details. */
final class BatchDayAlertDetails {

  private BatchDayAlertDetails() {}

  static Map<String, Object> base(LaunchRequest request, String reasonCode) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tenantId", request.tenantId());
    detail.put("jobCode", request.jobCode());
    detail.put("bizDate", request.bizDate() == null ? null : request.bizDate().toString());
    detail.put("requestId", request.requestId());
    detail.put(
        "triggerType",
        request.triggerType() == null ? null : request.triggerType().code());
    detail.put("reasonCode", reasonCode);
    return detail;
  }
}
