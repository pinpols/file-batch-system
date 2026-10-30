package io.github.pinpols.batch.console.domain.ops.view;

import java.time.Instant;

/**
 * batch.config_approval 行投影（ConfigApprovalMapper.selectLatestByRelease / selectById，固定 12 列）。
 *
 * <p>reviewed_by / reviewed_at / review_comment / expired_at 在 PENDING 状态下可为空。
 */
public record ConfigApprovalRow(
    Long id,
    String tenantId,
    Long releaseId,
    String approvalStatus,
    String requestedBy,
    Instant requestedAt,
    String reviewedBy,
    Instant reviewedAt,
    String reviewComment,
    Instant expiredAt,
    Instant createdAt,
    Instant updatedAt) {}
