package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowNodeRunEntity {

  private Long id;
  private Long workflowRunId;
  private String nodeCode;
  private String nodeType;
  private Integer runSeq;
  private String nodeStatus;
  private Integer retryCount;
  private String errorCode;
  private String errorMessage;
  private Instant startedAt;
  private Instant finishedAt;
  private Long durationMs;
}
