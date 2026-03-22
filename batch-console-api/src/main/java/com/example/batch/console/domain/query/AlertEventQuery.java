package com.example.batch.console.domain.query;

public record AlertEventQuery(
        String tenantId,
        String severity,
        String status,
        String alertType,
        Integer limit
) {
}
