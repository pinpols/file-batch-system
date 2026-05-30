package com.example.batch.console.domain.job.query;

import com.example.batch.common.model.PageRequest;

public record JobStepInstanceQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String stepCode,
    String stepStatus,
    PageRequest pageRequest,
    Long cursorId) {}
