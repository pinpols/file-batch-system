package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Data;

/**
 * PERF(5.3): {@code JobPartitionMapper.renewLeaseBatch} 的 RETURNING 行 —— 续租成功的 (tenantId, taskId)
 * 及该 task 的 {@code cancel_requested} 标记（随批量续租一并带回，替代原先心跳里的第二次 selectById）。
 */
@Data
public class RenewLeaseBatchRow {
  private String tenantId;
  private Long taskId;
  private Boolean cancelRequested;
}
