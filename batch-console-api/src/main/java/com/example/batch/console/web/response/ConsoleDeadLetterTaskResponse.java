package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleDeadLetterTaskResponse(
        Long id,
        String tenantId,
        String sourceType,
        Long sourceId,
        String deadLetterReason,
        String payloadRef,
        String replayStatus,
        Integer replayCount,
        Instant lastReplayAt,
        String lastReplayResult,
        String traceId,
        Instant createdAt,
        Instant updatedAt
) {
}
