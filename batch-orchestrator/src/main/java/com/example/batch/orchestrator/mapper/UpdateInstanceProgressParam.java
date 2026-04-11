package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class UpdateInstanceProgressParam {
    private final String tenantId;
    private final Long id;
    private final String instanceStatus;
    private final Integer successPartitionCount;
    private final Integer failedPartitionCount;
    private final String resultSummary;
    private final Instant finishedAt;
    private final Long expectedVersion;
}
