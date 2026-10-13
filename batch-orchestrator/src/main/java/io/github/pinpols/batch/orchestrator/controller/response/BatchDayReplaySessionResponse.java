package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import java.time.Instant;
import java.time.LocalDate;

/** 重放会话的 HTTP 契约，与持久化行模型保持独立。 */
public record BatchDayReplaySessionResponse(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    String executionMode,
    String candidateSource,
    String scopePayload,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String reason,
    Long approvalId,
    String status,
    Integer totalCount,
    Integer succeededCount,
    Integer failedCount,
    Integer inFlightCount,
    String requestedBy,
    String approvedBy,
    Instant startedAt,
    Instant completedAt,
    String traceId,
    Instant createdAt,
    Instant updatedAt) {

  public static BatchDayReplaySessionResponse from(BatchDayReplaySessionEntity entity) {
    return new BatchDayReplaySessionResponse(
        entity.id(),
        entity.tenantId(),
        entity.calendarCode(),
        entity.bizDate(),
        entity.scope(),
        entity.executionMode(),
        entity.candidateSource(),
        entity.scopePayload(),
        entity.resultPolicy(),
        entity.configVersionPolicy(),
        entity.configVersion(),
        entity.reason(),
        entity.approvalId(),
        entity.status(),
        entity.totalCount(),
        entity.succeededCount(),
        entity.failedCount(),
        entity.inFlightCount(),
        entity.requestedBy(),
        entity.approvedBy(),
        entity.startedAt(),
        entity.completedAt(),
        entity.traceId(),
        entity.createdAt(),
        entity.updatedAt());
  }
}
