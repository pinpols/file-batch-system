package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobTaskQuery(
        String tenantId,
        Long jobInstanceId,
        Long jobPartitionId,
        String taskStatus,
        PageRequest pageRequest) {}
