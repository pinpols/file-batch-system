package com.example.batch.worker.core.support;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.worker.core.domain.TaskExecutionReport;
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
   * ADR-016: renew many tasks in as few orchestrator HTTP calls as possible. Per-task values mirror
   * single {@link #renewLease}; on batch transport failure implementations should fall back to
   * per-task {@link #renewLease}.
   */
  Map<Long, Boolean> renewLeasesBatch(List<TaskLeaseRenewItem> items);

  void report(TaskExecutionReport report);
}
