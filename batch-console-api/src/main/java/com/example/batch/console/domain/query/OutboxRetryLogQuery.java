package com.example.batch.console.domain.query;

public record OutboxRetryLogQuery(
        String tenantId,
        String retryStatus,
        String eventKey
) {
}
