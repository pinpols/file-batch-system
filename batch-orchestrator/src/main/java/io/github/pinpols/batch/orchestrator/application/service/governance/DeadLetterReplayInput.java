package io.github.pinpols.batch.orchestrator.application.service.governance;

import lombok.Builder;

/**
 * 尚待应用服务验证的手工重放输入。
 * 外部结果声明必须经过原有证据校验与专用补偿入口，不能凭此输入直接恢复执行。
 */
@Builder
public record DeadLetterReplayInput(
    String tenantId,
    Long deadLetterTaskId,
    String operatorId,
    String reason,
    String idempotencyKey,
    boolean externalActionVerifiedNotExecuted,
    String externalOutcomeEvidenceReference) {

  @Override
  public String toString() {
    return "DeadLetterReplayInput[redacted]";
  }
}
