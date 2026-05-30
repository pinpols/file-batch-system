package com.example.batch.console.domain.job.query;

import com.example.batch.common.model.PageRequest;

public record JobPartitionQuery(
    String tenantId,
    Long jobInstanceId,
    String partitionStatus,
    PageRequest pageRequest,
    Long cursorId) {}
