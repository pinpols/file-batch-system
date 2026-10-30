package io.github.pinpols.batch.console.domain.audit.view;

import java.time.Instant;

/**
 * batch.file_audit_log 行投影（AuditLogMapper.selectByQuery，固定 11 列，snake_case 列名）。
 */
public record FileAuditLogRow(
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
