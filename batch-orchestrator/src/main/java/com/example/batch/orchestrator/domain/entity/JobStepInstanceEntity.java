package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.LocalizedErrorCarrier;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

@Data
public class JobStepInstanceEntity implements Stateful, LocalizedErrorCarrier {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Long jobPartitionId;
  private Long jobTaskId;
  private String stepCode;
  private String stepType;
  private String stepStatus;

  /** 步骤生命周期内的业务重试次数。 */
  private Integer retryCount;

  private Long relatedFileId;
  private String resultSummary;
  private String errorCode;
  private String errorMessage;

  /** i18n message key,V78+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private String errorArgs;

  private Long version;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  @Override
  public String getStatus() {
    return stepStatus;
  }
}
