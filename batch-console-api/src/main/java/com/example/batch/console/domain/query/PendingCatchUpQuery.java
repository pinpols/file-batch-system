package com.example.batch.console.domain.query;

public record PendingCatchUpQuery(
        String tenantId,
        String jobCode,
        String requestId
) {
}
