package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;

public record JobInstanceQuery(
    String tenantId,
    String jobCode,
    String instanceStatus,
    String instanceNo,
    String bizDate,
    String traceId,
    Instant startedFrom,
    Instant startedTo,
    String sortBy,
    Integer minDurationSeconds,
    PageRequest pageRequest) {}
