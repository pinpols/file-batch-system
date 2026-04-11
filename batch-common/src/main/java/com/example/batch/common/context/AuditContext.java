package com.example.batch.common.context;

public record AuditContext(
        String tenantId, String operatorId, String operatorType, String traceId) {}
