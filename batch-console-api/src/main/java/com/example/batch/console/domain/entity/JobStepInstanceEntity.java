package com.example.batch.console.domain.entity;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.time.Instant;
import lombok.Data;

@Data
public class JobStepInstanceEntity extends AbstractLocalizedErrorEntity {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Long jobPartitionId;
  private Long jobTaskId;
  private String stepCode;
  private String stepType;
  private String stepStatus;
  private Integer retryCount;
  private Long relatedFileId;
  private String resultSummary;
  private String errorCode;

  private Instant startedAt;
  private Instant finishedAt;
}
