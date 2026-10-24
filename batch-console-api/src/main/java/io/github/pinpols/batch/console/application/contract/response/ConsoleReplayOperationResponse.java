package io.github.pinpols.batch.console.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;

/** ADR-049 重放操作的审计根与聚合进度。 */
public record ConsoleReplayOperationResponse(
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
    Instant updatedAt) {}
