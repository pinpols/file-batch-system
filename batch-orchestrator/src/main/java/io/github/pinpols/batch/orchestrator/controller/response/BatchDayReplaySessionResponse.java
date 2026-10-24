package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 重放会话的 HTTP 契约，与持久化行模型保持独立。 */
@Builder
public record BatchDayReplaySessionResponse(
    Long id,
    String tenantId,
    Long replayOperationId,
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
    return BatchDayReplaySessionResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .replayOperationId(entity.replayOperationId())
        .calendarCode(entity.calendarCode())
        .bizDate(entity.bizDate())
        .scope(entity.scope())
        .executionMode(entity.executionMode())
        .candidateSource(entity.candidateSource())
        .scopePayload(entity.scopePayload())
        .resultPolicy(entity.resultPolicy())
        .configVersionPolicy(entity.configVersionPolicy())
        .configVersion(entity.configVersion())
        .reason(entity.reason())
        .approvalId(entity.approvalId())
        .status(entity.status())
        .totalCount(entity.totalCount())
        .succeededCount(entity.succeededCount())
        .failedCount(entity.failedCount())
        .inFlightCount(entity.inFlightCount())
        .requestedBy(entity.requestedBy())
        .approvedBy(entity.approvedBy())
        .startedAt(entity.startedAt())
        .completedAt(entity.completedAt())
        .traceId(entity.traceId())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .build();
  }
}
