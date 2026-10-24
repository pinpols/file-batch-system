package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.EmptyChecks;

/** 将冻结 Workflow 快照中的可信重放准入元数据传递到 Worker CLAIM。 */
public record ReplayExecutionGuard(
    Long replayOperationId, ReplayCapabilityDeclaration declaration) {

  public ReplayExecutionGuard {
    if (EmptyChecks.isNull(replayOperationId) || replayOperationId <= 0) {
      throw new IllegalArgumentException("replayOperationId must be positive");
    }
    boolean replayDeclarationIsUnsafe = EmptyChecks.isNull(declaration)
        || !declaration.isFullyDeclared()
        || declaration.idempotencyCapability() == IdempotencyCapability.NONE;
    if (replayDeclarationIsUnsafe) {
      throw new IllegalArgumentException("replay declaration must be complete and replayable");
    }
  }
}
