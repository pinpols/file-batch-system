package com.example.batch.common.kafka;

import java.time.Instant;
import java.util.Map;

public record BatchDeadLetterMessage(
        String schemaVersion,
        String tenantId,
        String jobCode,
        String instanceNo,
        String partitionId,
        String taskId,
        String deadLetterKey,
        String idempotencyKey,
        String traceId,
        /**
         * Message delivery attempt sequence when the record is sent to DLQ.
         */
        int attemptNo,
        String deadReason,
        Instant deadAt,
        Map<String, Object> payload
) {
}
