package com.example.batch.orchestrator.domain.entity;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
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

  /** V88: 拷自 job_definition.priority, scheduler/selector 按 desc 排序优先派发. 默认 5 (范围 0-10). */
  private Integer priority;

  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  /** ADR-012 task 级失败分类（V111）。worker REPORT 失败时上报，FailureClass 取值。 */
  private String failureClass;

  @Override
  public String getStatus() {
    return taskStatus;
  }
}
