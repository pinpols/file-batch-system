package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

/** 仅保存摘要的一次性重放操作预览凭证。 */
@Builder
public record ReplayOperationPreviewTokenEntity(
    String tenantId,
    String tokenHash,
    String operationKey,
    String requestHash,
    String snapshotHash,
    String snapshotPayload,
    Instant expiresAt,
    Instant consumedAt,
    Long operationId,
    Instant createdAt) {}
