package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

public record OutboxEventQuery(
        String tenantId,
        String publishStatus,
        String aggregateType,
        PageRequest pageRequest
) {
}
