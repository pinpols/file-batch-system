package com.example.batch.console.domain.ops.web.response;

import java.time.Instant;

public record ConsoleAuditLogResponse(
    Long id,
    String tenantId,
    Long fileId,
    String operationType,
    String operationResult,
    String operatorType,
    String operatorId,
    String traceId,
    String evidenceRef,
    String detailSummary,
    Instant createdAt) {}
