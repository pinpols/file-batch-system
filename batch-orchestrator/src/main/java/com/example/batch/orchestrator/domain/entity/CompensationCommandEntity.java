package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class CompensationCommandEntity extends AbstractLocalizedErrorEntity {

  private Long id;
  private String tenantId;
  private String commandNo;
  private String compensationType;
  private Long targetId;
  private String jobCode;
  private LocalDate bizDate;
  private String batchNo;
  private Long relatedJobInstanceId;
  private Long relatedFileId;
  private String approvalId;
  private String operatorId;
  private String reason;
  private String strategy;
  private String commandStatus;
  private String traceId;
  private String resultSummary;
  private String errorCode;

  private Instant createdAt;
  private Instant finishedAt;
}
