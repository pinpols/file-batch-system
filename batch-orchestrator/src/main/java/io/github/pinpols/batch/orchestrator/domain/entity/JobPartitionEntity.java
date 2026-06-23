package io.github.pinpols.batch.orchestrator.domain.entity;

import io.github.pinpols.batch.orchestrator.domain.statemachine.Stateful;
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

  /** ADR-046 per-file 绑定:束内本 partition 绑定的源文件 id（由 launch 按 manifest 展开束时写入）。 */
  private Long sourceFileId;

  /** ADR-046 per-file 绑定:本 partition 用的文件模板 code（异构束内各 partition 可不同）。 */
  private String templateCode;

  /** ADR-046 per-file 绑定:目标引用（导入=目标表 / 导出=源表查询 / 分发=下游渠道）。 */
  private String targetRef;

  @Override
  public String getStatus() {
    return partitionStatus;
  }
}
