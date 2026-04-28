package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FinishTaskParam {
  private final String tenantId;
  private final Long id;
  private final String taskStatus;
  private final String expectedStatus;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;

  /** i18n message key,V77+ 写入 job_task.error_key。 */
  private final String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private final String errorArgs;

  private final Long expectedVersion;
}
