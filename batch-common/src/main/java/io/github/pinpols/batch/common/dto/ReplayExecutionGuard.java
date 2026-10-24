package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;

/** Trusted replay admission metadata carried from the frozen Workflow snapshot to Worker CLAIM. */
public record ReplayExecutionGuard(
    Long replayOperationId, ReplayCapabilityDeclaration declaration) {

  public ReplayExecutionGuard {
    if (replayOperationId == null || replayOperationId <= 0) {
      throw new IllegalArgumentException("replayOperationId must be positive");
    }
    if (declaration == null
        || !declaration.isFullyDeclared()
        || declaration.idempotencyCapability() == IdempotencyCapability.NONE) {
      throw new IllegalArgumentException("replay declaration must be complete and replayable");
    }
  }
}
