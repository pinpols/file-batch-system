package io.github.pinpols.batch.common.context;

public record AuditContext(
    String tenantId, String operatorId, String operatorType, String traceId) {}
