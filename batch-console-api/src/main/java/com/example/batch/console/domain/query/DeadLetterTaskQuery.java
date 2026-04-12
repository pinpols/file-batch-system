package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record DeadLetterTaskQuery(
    String tenantId,
    String sourceType,
    String replayStatus,
    String traceId,
    PageRequest pageRequest) {}
