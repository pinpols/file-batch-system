package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record JobInstanceQuery(
        String tenantId,
        String jobCode,
        String instanceStatus,
        String instanceNo,
        String bizDate,
        String traceId,
        PageRequest pageRequest
) {
}
