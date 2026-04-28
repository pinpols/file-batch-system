package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class JobStepInstanceEntity {

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
  private String errorMessage;

  /** i18n message key,V78+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private String errorArgs;

  private Instant startedAt;
  private Instant finishedAt;
}
