package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MarkRunningParam {
  private final String tenantId;
  private final Long id;
  private final Instant startedAt;
  private final Long expectedVersion;
  private final String runningStatus;
  private final String createdStatus;
  private final String waitingStatus;
  private final String readyStatus;
  private final String retryingStatus;
}
