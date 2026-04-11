package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class ClaimPartitionParam {
    private final String tenantId;
    private final Long id;
    private final String workerCode;
    private final Instant leaseExpireAt;
    private final String fromStatus;
    private final String toStatus;
    private final Long expectedVersion;
}
