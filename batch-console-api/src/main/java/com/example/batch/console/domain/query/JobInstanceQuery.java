package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;
import lombok.Builder;

@Builder
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
    Boolean slaBreached,
    PageRequest pageRequest) {}
