package io.github.pinpols.batch.console.domain.notification.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;

public record AlertEventQuery(
    String tenantId,
    String severity,
    String status,
    String alertType,
    String traceId,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest,
    Long cursorId) {

  public static AlertEventQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, null, null, null, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofSeverity(
      String tenantId, String severity, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, severity, null, null, null, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofStatus(String tenantId, String status, PageRequest pageRequest) {
    return new AlertEventQuery(tenantId, null, status, null, null, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofAlertType(
      String tenantId, String alertType, PageRequest pageRequest) {
    return new AlertEventQuery(
        tenantId, null, null, alertType, null, null, null, pageRequest, null);
  }

  public static AlertEventQuery ofLastSeenRange(
      String tenantId, Instant fromTime, Instant toTime, PageRequest pageRequest) {
    return new AlertEventQuery(
        tenantId, null, null, null, null, fromTime, toTime, pageRequest, null);
  }
}
