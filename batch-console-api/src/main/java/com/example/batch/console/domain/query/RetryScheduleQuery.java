package com.example.batch.console.domain.query;

public record RetryScheduleQuery(
        String tenantId,
        String relatedType,
        String retryPolicy,
        String retryStatus
) {
}
