package com.example.batch.orchestrator.domain.param;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CountActiveByGroupParam {
  private final String tenantId;
  private final String workerGroup;
  private final String waitingStatus;
  private final String readyStatus;
  private final String runningStatus;
  private final String retryingStatus;
}
