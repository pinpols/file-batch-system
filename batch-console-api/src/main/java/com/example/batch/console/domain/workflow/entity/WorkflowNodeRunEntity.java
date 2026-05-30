package com.example.batch.console.domain.workflow.entity;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class WorkflowNodeRunEntity extends AbstractLocalizedErrorEntity {

  private Long id;
  private Long workflowRunId;
  private String nodeCode;
  private String nodeType;
  private Integer runSeq;
  private String nodeStatus;
  private Integer retryCount;
  private String errorCode;

  private Instant startedAt;
  private Instant finishedAt;
  private Long durationMs;
}
