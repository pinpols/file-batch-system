package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;
import lombok.Getter;

/**
 * PERF(5.3): {@code JobPartitionMapper.renewLeaseBatch} 的单行入参 —— 批量续租 VALUES 行 (tenantId, taskId,
 * workerCode, invocationId)。调用方（{@code DefaultTaskAssignmentService.renewLeaseBatch}）保证四字段均非空： 空值项在
 * Java 侧直接判失败，不进 SQL（与单条链路的前置校验语义一致）。
 */
@Getter
@Builder
public class RenewLeaseBatchItem {
  private final String tenantId;
  private final Long taskId;
  private final String workerCode;

  /** R3-P1-10: 强制匹配 {@code current_invocation_id}，防多副本同 workerCode 续他人 lease。 */
  private final String invocationId;
}
