package com.example.batch.common.kafka;

import java.time.Instant;
import java.util.Map;

public record BatchRetryMessage(
        String schemaVersion,
        String tenantId,
        String jobCode,
        String instanceNo,
        String partitionId,
        String taskId,
        String retryKey,
        String idempotencyKey,
        String traceId,
        /**
         * Message delivery attempt sequence for retry scheduling.
         *
         * <p>This is not the same as a DB-side business retry counter.
         */
        int attemptNo,
        int maxAttempts,
        Instant nextRetryAt,
        String retryReason,
        Map<String, Object> payload
) {
}
