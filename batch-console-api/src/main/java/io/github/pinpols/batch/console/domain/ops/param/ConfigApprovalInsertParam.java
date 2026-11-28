package io.github.pinpols.batch.console.domain.ops.param;

import java.time.Instant;

import io.github.pinpols.batch.common.enums.ApprovalCommandStatus;

public record ConfigApprovalInsertParam(
        String tenantId, Long releaseId, String requestedBy, String reviewComment, Instant expiredAt) {

    public String approvalStatus() {
        return ApprovalCommandStatus.PENDING.code();
    }
}
