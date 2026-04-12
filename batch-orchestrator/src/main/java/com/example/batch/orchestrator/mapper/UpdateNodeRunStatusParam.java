package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateNodeRunStatusParam {
  private final Long id;
  private final String nodeStatus;
  private final String errorCode;
  private final String errorMessage;
  private final Long durationMs;
  private final Instant finishedAt;
}
