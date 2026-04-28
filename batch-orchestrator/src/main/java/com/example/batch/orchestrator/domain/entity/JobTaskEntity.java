package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.LocalizedErrorCarrier;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

@Data
public class JobTaskEntity implements Stateful, LocalizedErrorCarrier {

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
  private String errorMessage;

  /** i18n message key,V77+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组,与 errorKey 一起支持历史日志按 Locale 重渲染。 */
  private String errorArgs;

  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  @Override
  public String getStatus() {
    return taskStatus;
  }
}
