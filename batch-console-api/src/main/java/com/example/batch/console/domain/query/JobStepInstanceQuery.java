package com.example.batch.console.domain.query;

public record JobStepInstanceQuery(
        String tenantId,
        Long jobInstanceId,
        Long jobPartitionId,
        String stepCode,
        String stepStatus
) {
}
