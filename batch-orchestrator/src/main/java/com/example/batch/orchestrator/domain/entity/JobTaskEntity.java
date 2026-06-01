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

  /**
   * ORCH-P3-3 派单期生效参数快照(descriptor.defaults &lt; default_params &lt; request.params 合并后);审计/排障用, 与
   * {@code taskPayload}(投递 worker 的 wire 载荷,含运行态注入)解耦。JSON 文本,mapper {@code ::jsonb} 入库。敏感凭据禁入。
   */
  private String effectiveParameters;

  /** ORCH-P4-1 worker 经 renew 上报的进度 / checkpoint 快照(JSON 文本，mapper {@code ::jsonb} 入库)。敏感凭据禁入。 */
  private String heartbeatDetails;

  /** ORCH-P4-1 最近一次带 details 的心跳时间(UTC)。 */
  private Instant heartbeatAt;

  /** ORCH-P4-1 平台请求取消标记；renew response 回带，SDK 主动停(不等 lease 超时)。 */
  private Boolean cancelRequested;

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

  /** ADR-026 dry-run 演练标记；从父 job_partition.dry_run 透传。 */
  private Boolean dryRun;

  /** ORCH-P4-2 派单期拷自 workflow_node.task_timeout_seconds；TaskTimeoutEnforcer 据此软取消超时 task。 */
  private Integer taskTimeoutSeconds;

  @Override
  public String getStatus() {
    return taskStatus;
  }
}
