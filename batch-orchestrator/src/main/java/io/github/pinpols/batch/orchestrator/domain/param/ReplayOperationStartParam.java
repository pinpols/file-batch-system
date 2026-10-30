package io.github.pinpols.batch.orchestrator.domain.param;

import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import java.time.Instant;
import lombok.Builder;

/** 审批后提升到执行态的精确待处理 Workflow 重放身份。 */
@Builder
public record ReplayOperationStartParam(
    String tenantId, Long operationId, String targetRequestId, String approver, Instant now) {

  public String targetStatus() {
    return ReplayOperationStatus.RUNNING.code();
  }
}
