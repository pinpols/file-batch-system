package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record DeadLetterTaskQuery(
    String tenantId,
    String sourceType,
    String replayStatus,
    String traceId,
    PageRequest pageRequest) {

  public static DeadLetterTaskQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, null, null, null, pageRequest);
  }

  public static DeadLetterTaskQuery ofSourceType(
      String tenantId, String sourceType, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, sourceType, null, null, pageRequest);
  }

  public static DeadLetterTaskQuery ofReplayStatus(
      String tenantId, String replayStatus, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, null, replayStatus, null, pageRequest);
  }

  public static DeadLetterTaskQuery ofTraceId(
      String tenantId, String traceId, PageRequest pageRequest) {
    return new DeadLetterTaskQuery(tenantId, null, null, traceId, pageRequest);
  }
}
