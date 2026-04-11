package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class TouchHeartbeatParam {
    private final String tenantId;
    private final String workerCode;
    private final String nextStatus;
    private final Instant heartbeatAt;
    private final Integer currentLoad;
    private final String capabilityTags;
}
