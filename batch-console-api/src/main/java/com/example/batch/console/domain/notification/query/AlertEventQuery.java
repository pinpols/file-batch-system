package com.example.batch.console.domain.notification.query;

import com.example.batch.common.model.PageRequest;

public record AlertEventQuery(
    String tenantId,
    String severity,
    String status,
    String alertType,
    String traceId,
    PageRequest pageRequest,
    Long cursorId) {

  public static AlertEventQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, null, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofSeverity(
      String tenantId, String severity, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, severity, null, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofStatus(String tenantId, String status, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, status, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofAlertType(
      String tenantId, String alertType, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, null, alertType, null, pageRequest, null);
  }
}
