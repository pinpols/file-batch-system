package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobPartitionQuery(
        String tenantId,
        Long jobInstanceId,
        String partitionStatus,
        PageRequest pageRequest
) {
}
