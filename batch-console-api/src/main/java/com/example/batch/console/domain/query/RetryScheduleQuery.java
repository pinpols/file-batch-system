package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record RetryScheduleQuery(
    String tenantId,
    String relatedType,
    String retryPolicy,
    String retryStatus,
    PageRequest pageRequest) {

  public static RetryScheduleQuery ofTenant(String tenantId, PageRequest pageRequest) {
    return new RetryScheduleQuery(tenantId, null, null, null, pageRequest);
  }

  public static RetryScheduleQuery ofRelatedType(
      String tenantId, String relatedType, PageRequest pageRequest) {
    return new RetryScheduleQuery(tenantId, relatedType, null, null, pageRequest);
  }

  public static RetryScheduleQuery ofRetryStatus(
      String tenantId, String retryStatus, PageRequest pageRequest) {
    return new RetryScheduleQuery(tenantId, null, null, retryStatus, pageRequest);
  }

  public static RetryScheduleQuery ofRetryPolicy(
      String tenantId, String retryPolicy, PageRequest pageRequest) {
    return new RetryScheduleQuery(tenantId, null, retryPolicy, null, pageRequest);
  }
}
