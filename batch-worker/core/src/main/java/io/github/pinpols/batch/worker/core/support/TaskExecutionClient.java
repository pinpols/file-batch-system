package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TaskExecutionClient {

  /**
   * 向 orchestrator 发起 CLAIM。
   *
   * <p>P1-2.1 起 orchestrator 在认领成功(HTTP 200)时回 {@link EffectiveTaskConfig} body,worker 优先用其字段; 旧版
   * orchestrator 不返 body,worker 收到的 Optional 仍 present 但内含字段全为 null,fallback 到 {@code
   * TaskDispatchMessage} 旧字段。
   *
   * @return Optional.empty() 表示明确不可认领(HTTP 404/409);Optional.of(config) 表示成功；其他HTTP错误向上传播
   */
  Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId);

  /**
   * ADR-046 P2 切片 2.3:一次 HTTP 往返认领 K 个独立 task(O(N)→O(N/K))。逐项返回 {@link TaskClaimResult} (claimed +
   * config),语义与单条 {@link #claim} 一致;批量传输失败(404/400/5xx 耗尽/响应不匹配)时实现应回退到逐条 {@link #claim},保证可用性。
   */
  List<TaskClaimResult> claimBatch(List<TaskClaimItem> items);

  boolean renewLease(String tenantId, Long taskId, String workerId, String partitionInvocationId);

  /** 在非幂等业务副作用开始前登记执行起点；未获确认时不得继续外部动作。 */
  boolean markNonIdempotentExecutionStarted(
      String tenantId, Long taskId, String workerId, String partitionInvocationId);

  /**
   * 批量续租各任务的当前租约，普通续租失败时可回退单条续租。
   * 携带执行入口标记的项必须保留该标记独立确认，不能降级为普通续租后视为开始确认成功。
   */
  Map<Long, TaskLeaseRenewResult> renewLeasesBatch(List<TaskLeaseRenewItem> items);

  void report(TaskExecutionReport report);
}
