package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/**
 * 批量日治理操作审计行（V105 batch.batch_day_operation_audit）。
 *
 * <p>独立于 {@code job_execution_log}，专供批量日 freeze / release / skip / reopen / close 等高风险动作的 Console
 * 操作历史和 运维检索。写入与 {@code batch_day_instance} 状态变更同事务，由 {@link
 * com.example.batch.orchestrator.service.BatchDayOperationService} 触发。
 */
@Builder
public record BatchDayOperationAuditEntity(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String operationType,
    String fromStatus,
    String toStatus,
    Boolean fromFrozen,
    Boolean toFrozen,
    String operatorId,
    String operatorType,
    String reasonCode,
    String comment,
    Long approvalId,
    String requestPayload,
    String traceId,
    Instant createdAt) {}
