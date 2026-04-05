package com.example.batch.console.domain.query;

import java.time.Instant;
import com.example.batch.common.model.PageRequest;

public record JobInstanceQuery(
        String tenantId,
        String jobCode,
        String instanceStatus,
        String instanceNo,
        String bizDate,
        String traceId,
        Instant startedFrom,
        Instant startedTo,
        PageRequest pageRequest
) {
}
