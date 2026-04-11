package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class MarkInstanceRunningParam {
    private final String tenantId;
    private final Long id;
    private final String instanceStatus;
    private final Integer expectedPartitionCount;
    private final Instant startedAt;
    private final Long expectedVersion;
}
