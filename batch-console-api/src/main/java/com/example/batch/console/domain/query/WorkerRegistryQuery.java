package com.example.batch.console.domain.query;

public record WorkerRegistryQuery(
        String tenantId,
        String workerGroup,
        String status
) {
}
