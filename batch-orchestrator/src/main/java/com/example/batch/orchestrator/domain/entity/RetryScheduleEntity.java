package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class RetryScheduleEntity {

  private Long id;
  private String tenantId;
  private String relatedType;
  private Long relatedId;
  private String retryPolicy;
  private Integer retryCount;
  private Integer maxRetryCount;
  private Instant nextRetryAt;
  private String retryStatus;
  private String dedupKey;
  private String lastErrorCode;
  private String lastErrorMessage;

  /** i18n message key,V78+ 写入;读路径按当前 Locale 渲染时优先于 lastErrorMessage。 */
  private String lastErrorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private String lastErrorArgs;

  private Instant createdAt;
  private Instant updatedAt;
}
