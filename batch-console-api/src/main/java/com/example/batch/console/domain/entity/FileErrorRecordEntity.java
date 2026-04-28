package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class FileErrorRecordEntity {

  private Long id;
  private String tenantId;
  private Long fileId;
  private Long pipelineInstanceId;
  private Long pipelineStepRunId;
  private Long recordNo;
  private String errorCode;
  private String errorMessage;

  /** i18n message key,V78+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private String errorArgs;

  private String errorStage;
  private Boolean skipped;
  private String skipAction;
  private String rawRecord;
  private Instant createdAt;
}
