package io.github.pinpols.batch.console.domain.ops.param;

import io.github.pinpols.batch.common.enums.ApprovalCommandStatus;
import java.time.Instant;

public record ConfigApprovalInsertParam(
    String tenantId, Long releaseId, String requestedBy, String reviewComment, Instant expiredAt) {

  public String approvalStatus() {
    return ApprovalCommandStatus.PENDING.code();
  }
}
