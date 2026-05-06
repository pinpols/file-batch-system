package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.time.Instant;
import lombok.Data;

@Data
public class JobPartitionEntity implements Stateful {

  private Long id;
  private String tenantId;
  private Long jobInstanceId;
  private Integer partitionNo;
  private String partitionKey;
  private String partitionStatus;
  private String workerGroup;
  private String workerCode;
  private Instant leaseExpireAt;

  /** ADR-014: 本轮 READY→RUNNING claim 生成的 invocation id；回收/重试时清空。 */
  private String currentInvocationId;

  /** ADR-014: {@link #currentInvocationId} 写入时间（UTC）。 */
  private Instant invocationStartedAt;

  private Long version;

  /** 分区生命周期内的业务重试次数。 */
  private Integer retryCount;

  private String businessKey;
  private String idempotencyKey;
  private String inputSnapshot;
  private String outputSummary;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant createdAt;
  private Instant updatedAt;

  /** ADR-026 dry-run 演练标记；从父 job_instance.dry_run 透传。 */
  private Boolean dryRun;

  @Override
  public String getStatus() {
    return partitionStatus;
  }
}
