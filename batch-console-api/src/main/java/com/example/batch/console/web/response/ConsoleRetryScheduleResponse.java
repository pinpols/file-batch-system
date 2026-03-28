package com.example.batch.console.web.response;

import java.time.Instant;

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
        Instant updatedAt
) {
}
