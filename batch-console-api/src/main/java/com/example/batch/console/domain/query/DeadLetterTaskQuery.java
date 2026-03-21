package com.example.batch.console.domain.query;

public record DeadLetterTaskQuery(
        String tenantId,
        String sourceType,
        String replayStatus,
        String traceId
) {
}
