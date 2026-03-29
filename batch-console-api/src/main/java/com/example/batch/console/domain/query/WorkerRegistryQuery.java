package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkerRegistryQuery(
        String tenantId,
        String workerGroup,
        String status,
        PageRequest pageRequest
) {
}
