package io.github.pinpols.batch.console.domain.job.application.contract.response;

import lombok.Builder;

import java.time.Instant;
@Builder
public record ConsoleRetryScheduleResponse(
    Long id,
    String tenantId,
    String relatedType,
    Long relatedId,
    String retryPolicy,
    Integer retryCount,
    Integer maxRetryCount,
    Instant nextRetryAt,
    String retryStatus,
    String dedupKey,
    String lastErrorCode,
    String lastErrorMessage,
    Instant createdAt,
    Instant updatedAt) {}
