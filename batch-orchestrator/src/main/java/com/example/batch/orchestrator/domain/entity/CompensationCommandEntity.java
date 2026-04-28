package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.LocalizedErrorCarrier;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class CompensationCommandEntity implements LocalizedErrorCarrier {

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
  private String errorMessage;

  /** i18n message key,V78+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private String errorArgs;

  private Instant createdAt;
  private Instant finishedAt;
}
