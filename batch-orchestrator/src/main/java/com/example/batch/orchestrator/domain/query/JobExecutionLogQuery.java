package com.example.batch.orchestrator.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobExecutionLogQuery(
        String tenantId,
        Long jobInstanceId,
        Long jobPartitionId,
        String logType,
        PageRequest pageRequest) {}
