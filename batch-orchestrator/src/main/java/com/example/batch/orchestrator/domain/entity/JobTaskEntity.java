package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

@Data
public class JobTaskEntity extends AbstractLocalizedErrorEntity implements Stateful {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Long jobPartitionId;
  private String taskType;
  private Integer taskSeq;
  private String taskStatus;
  private String assignedWorkerCode;
  private Long version;
  private String taskPayload;
  private String resultSummary;
  private String errorCode;

  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  @Override
  public String getStatus() {
    return taskStatus;
  }
}
