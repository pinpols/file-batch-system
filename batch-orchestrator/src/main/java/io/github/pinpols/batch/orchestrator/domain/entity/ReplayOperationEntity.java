package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** ADR-049 重放操作的持久父项与审计根。 */
@Builder(toBuilder = true)
public record ReplayOperationEntity(
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
    String scopePayload,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    Long sourceWorkflowRunId,
    Long sourceWorkflowNodeRunId,
    Long sourceJobInstanceId,
    String targetRequestId,
    Long targetWorkflowRunId,
    String requestHash,
    String snapshotHash,
    String snapshotPayload,
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
