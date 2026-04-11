package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

import java.time.Instant;

public record FileArrivalGroupQuery(
        String tenantId,
        String fileGroupCode,
        String arrivalState,
        Instant fromTime,
        Instant toTime,
        PageRequest pageRequest) {}
