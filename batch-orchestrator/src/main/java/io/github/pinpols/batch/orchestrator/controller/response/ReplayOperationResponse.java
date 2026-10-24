package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 公开重放审计与进度投影；冻结载荷和内部摘要保留在服务端。 */
@Builder
public record ReplayOperationResponse(
    Long id,
    String tenantId,
    String operationKey,
    String operationType,
    String status,
    String calendarCode,
    LocalDate fromDate,
    LocalDate throughDate,
    String scope,
    String executionMode,
    String candidateSource,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String reason,
    String requestedBy,
    String approvedBy,
    Integer totalCount,
    Integer succeededCount,
    Integer failedCount,
    Integer inFlightCount,
    Instant startedAt,
    Instant completedAt,
    String traceId,
    Instant createdAt,
    Instant updatedAt) {

  public static ReplayOperationResponse from(ReplayOperationEntity entity) {
    return ReplayOperationResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .operationKey(entity.operationKey())
        .operationType(entity.operationType())
        .status(entity.status())
        .calendarCode(entity.calendarCode())
        .fromDate(entity.fromDate())
        .throughDate(entity.throughDate())
        .scope(entity.scope())
        .executionMode(entity.executionMode())
        .candidateSource(entity.candidateSource())
        .resultPolicy(entity.resultPolicy())
        .configVersionPolicy(entity.configVersionPolicy())
        .configVersion(entity.configVersion())
        .reason(entity.reason())
        .requestedBy(entity.requestedBy())
        .approvedBy(entity.approvedBy())
        .totalCount(entity.totalCount())
        .succeededCount(entity.succeededCount())
        .failedCount(entity.failedCount())
        .inFlightCount(entity.inFlightCount())
        .startedAt(entity.startedAt())
        .completedAt(entity.completedAt())
        .traceId(entity.traceId())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .build();
  }
}
