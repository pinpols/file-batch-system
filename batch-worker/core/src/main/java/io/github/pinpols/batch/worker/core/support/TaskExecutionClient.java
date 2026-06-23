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
   * @return Optional.empty() 表示 CLAIM 失败(HTTP 4xx);Optional.of(config) 表示成功
   */
  Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId);

  boolean renewLease(String tenantId, Long taskId, String workerId, String partitionInvocationId);

  /**
   * ADR-016:用尽可能少的 orchestrator HTTP 调用续租多个 task。每个 task 的取值与单条 {@link #renewLease}
   * 一致;批量传输失败时,实现应回退到逐条 {@link #renewLease}。
   */
  Map<Long, TaskLeaseRenewResult> renewLeasesBatch(List<TaskLeaseRenewItem> items);

  void report(TaskExecutionReport report);
}
