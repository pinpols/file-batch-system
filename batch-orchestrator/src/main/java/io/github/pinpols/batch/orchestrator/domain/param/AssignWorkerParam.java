package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AssignWorkerParam {
  private final String tenantId;
  private final Long id;
  private final String assignedWorkerCode;
  /**
   * 调度阶段写入 task 的目标编码。它可能是具体实例 ID，也可能是稳定资源池编码；claim 成功后
   * {@link #assignedWorkerCode} 始终落为实际执行实例 ID。
   */
  private final String expectedAssignedWorkerCode;

  private final String taskStatus;
  private final String readyStatus;
  private final Long expectedVersion;
}
