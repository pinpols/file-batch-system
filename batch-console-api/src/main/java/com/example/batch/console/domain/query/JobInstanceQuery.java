package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record JobInstanceQuery(
    String tenantId,
    String jobCode,
    String instanceStatus,
    /** 多状态过滤;非空时 IN (...) 查询,优先于 instanceStatus 单值。 */
    List<String> instanceStatuses,
    String instanceNo,
    String bizDate,
    String traceId,
    Instant startedFrom,
    Instant startedTo,
    String sortBy,
    Integer minDurationSeconds,
    Boolean slaBreached,
    PageRequest pageRequest) {}
