package io.github.pinpols.batch.console.domain.governance.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record DeadLetterTaskQuery(
    String tenantId,
    String sourceType,
    String replayStatus,
    String traceId,
    PageRequest pageRequest,
    Long cursorId) {

  public static DeadLetterTaskQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, null, null, null, pageRequest, null);
  }

  public static DeadLetterTaskQuery ofSourceType(
      String tenantId, String sourceType, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, sourceType, null, null, pageRequest, null);
  }

  public static DeadLetterTaskQuery ofReplayStatus(
      String tenantId, String replayStatus, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, null, replayStatus, null, pageRequest, null);
  }

  public static DeadLetterTaskQuery ofTraceId(
      String tenantId, String traceId, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, null, null, traceId, pageRequest, null);
  }
}
