package com.example.batch.common.persistence.entity;

import com.example.batch.common.persistence.Stateful;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

// #8-1: 实现 Stateful 接口，消除 DefaultStateMachine 中的反射兜底路径
@Data
public class WorkflowRunEntity implements Stateful {

  private Long id;
  private String tenantId;
  private Long workflowDefinitionId;
  private Long relatedJobInstanceId;
  private LocalDate bizDate;
  private String runStatus;
  private String currentNodeCode;
  private String traceId;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  @Override
  public String getStatus() {
    return runStatus;
  }
}
