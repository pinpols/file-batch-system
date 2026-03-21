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
        int attemptNo,
        int maxAttempts,
        Instant nextRetryAt,
        String retryReason,
        Map<String, Object> payload
) {
}
