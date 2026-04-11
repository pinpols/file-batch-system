package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobStepInstanceQuery(
        String tenantId,
        Long jobInstanceId,
        Long jobPartitionId,
        String stepCode,
        String stepStatus,
        PageRequest pageRequest) {}
