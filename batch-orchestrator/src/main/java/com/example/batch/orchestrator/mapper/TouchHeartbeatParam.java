package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

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
