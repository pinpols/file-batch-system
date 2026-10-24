package io.github.pinpols.batch.console.shared.view;

import lombok.Builder;

import java.time.Instant;
@Builder
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
