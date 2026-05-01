package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record AlertEventQuery(
    String tenantId, String severity, String status, String alertType, PageRequest pageRequest) {

  public static AlertEventQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, null, null, pageRequest);
  }

  public static AlertEventQuery ofSeverity(
      String tenantId, String severity, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, severity, null, null, pageRequest);
  }

  public static AlertEventQuery ofStatus(String tenantId, String status, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, status, null, pageRequest);
  }

  public static AlertEventQuery ofAlertType(
      String tenantId, String alertType, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, null, alertType, pageRequest);
  }
}
