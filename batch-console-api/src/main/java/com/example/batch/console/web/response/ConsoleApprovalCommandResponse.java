package com.example.batch.console.web.response;

import java.time.OffsetDateTime;

public record ConsoleApprovalCommandResponse(
        Long id,
        String tenantId,
        String approvalNo,
        String approvalType,
        String actionType,
        String targetType,
        String targetId,
        String payloadJson,
        String approvalStatus,
        String requesterId,
        String approverId,
        String rejectionReason,
        String approvalReason,
        String sourceTraceId,
        String sourceIdempotencyKey,
        OffsetDateTime approvedAt,
        OffsetDateTime executedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
