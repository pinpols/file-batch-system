package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import java.time.Instant;
import java.time.LocalDate;

/** Public replay audit/progress projection; frozen payloads and internal hashes stay server-side. */
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
    return new ReplayOperationResponse(
        entity.id(),
        entity.tenantId(),
        entity.operationKey(),
        entity.operationType(),
        entity.status(),
        entity.calendarCode(),
        entity.fromDate(),
        entity.throughDate(),
        entity.scope(),
        entity.executionMode(),
        entity.candidateSource(),
        entity.resultPolicy(),
        entity.configVersionPolicy(),
        entity.configVersion(),
        entity.reason(),
        entity.requestedBy(),
        entity.approvedBy(),
        entity.totalCount(),
        entity.succeededCount(),
        entity.failedCount(),
        entity.inFlightCount(),
        entity.startedAt(),
        entity.completedAt(),
        entity.traceId(),
        entity.createdAt(),
        entity.updatedAt());
  }
}
