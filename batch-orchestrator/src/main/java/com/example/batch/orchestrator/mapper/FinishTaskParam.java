package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FinishTaskParam {
  private final String tenantId;
  private final Long id;
  private final String taskStatus;
  private final String expectedStatus;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;
  private final Long expectedVersion;
}
