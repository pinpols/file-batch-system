package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import java.util.List;
import java.util.Optional;

public interface TaskExecutionWrapper {

  /** 透传 {@link TaskExecutionClient#claim} 语义:Optional.empty=失败,Optional.of(config)=成功。 */
  Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId);

  /** 透传 {@link TaskExecutionClient#claimBatch}(ADR-046 P2 切片 2.3):一次往返认领 K 个。 */
  List<TaskClaimResult> claimBatch(List<TaskClaimItem> items);

  /** 批量 CLAIM 成功后先登记所有待执行租约，使续租器覆盖当前批次中仍在排队的任务。 */
  void registerClaimedBatch(List<PulledTask> tasks);

  /** 批次退出后停止为本批未启动项续租；已执行项和其他 invocation 不受影响。 */
  void releaseUnexecutedClaims(List<PulledTask> tasks);

  WorkerExecutionResult execute(PulledTask task);
}
