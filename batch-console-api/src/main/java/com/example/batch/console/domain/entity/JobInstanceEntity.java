package com.example.batch.console.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class JobInstanceEntity {

  private Long id;
  private String tenantId;
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
  private String traceId;
  private String paramsSnapshot;

  /** 增量模式下本次执行的水位起点(由 orchestrator 派发时填),见 V73 migration。 */
  private String highWaterMarkIn;

  /** 增量模式下本次执行结束后的新水位(由 worker 完成时上报)。 */
  private String highWaterMarkOut;

  private String resultSummary;
  private Instant deadlineAt;
  private Integer expectedDurationSeconds;
  private Instant slaAlertedAt;
  private Instant startedAt;
  private Instant finishedAt;

  /** ADR-026 dry-run 演练标记。 */
  private Boolean dryRun;

  /** ADR-012 失败分类。 */
  private String failureClass;
}
