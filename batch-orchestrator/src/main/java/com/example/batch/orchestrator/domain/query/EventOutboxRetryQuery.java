package com.example.batch.orchestrator.domain.query;

public record EventOutboxRetryQuery(
        String tenantId,
        String retryStatus,
        String eventKey
) {
}
