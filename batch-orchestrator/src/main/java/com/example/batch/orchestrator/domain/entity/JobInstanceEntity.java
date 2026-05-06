package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobInstanceEntity implements Stateful {

  private Long id;
  private String tenantId;
  private Long jobDefinitionId;
  private Long triggerRequestId;
  private String jobCode;
  private String instanceNo;
  private LocalDate bizDate;
  private String triggerType;
  private String instanceStatus;
  private String batchNo;
  private String operatorId;
  private Boolean rerunFlag;
  private Boolean retryFlag;
  private String rerunReason;
  private Long relatedFileId;
  private Long parentInstanceId;
  private String queueCode;
  private String workerGroup;
  private Integer priority;
  private String dedupKey;
  private Integer runAttempt;
  private Integer jobDefinitionVersion;
  private String rerunPolicySnapshot;
  private Long version;
  private Integer expectedPartitionCount;
  private Integer successPartitionCount;
  private Integer failedPartitionCount;
  private String traceId;
  private String paramsSnapshot;

  /** V93 P0-4: 创建时从 job_definition.calendar_code 抓快照, 与 batch_day_instance 关联, 不随 config 变更漂移. */
  private String calendarCode;

  /** V94: data_interval 半开区间起点 (Airflow 风格), 业务 SQL 拼 WHERE update_time >= :start. */
  private Instant dataIntervalStart;

  /** V94: data_interval 半开区间终点 (Airflow 风格), 业务 SQL 拼 WHERE update_time < :end. */
  private Instant dataIntervalEnd;

  /** 增量模式下本次执行的水位起点(orchestrator 派发时从上次成功实例的 OUT 读出)。 */
  private String highWaterMarkIn;

  /** 增量模式下本次执行结束后的新水位(worker 完成时上报)。 */
  private String highWaterMarkOut;

  private String resultSummary;
  private Instant deadlineAt;
  private Integer expectedDurationSeconds;
  private Instant slaAlertedAt;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  /** ADR-020 batch_day_replay_session.id 反查标签；NULL = 非 replay 创建。 */
  private Long replaySessionId;

  @Override
  public String getStatus() {
    return instanceStatus;
  }
}
