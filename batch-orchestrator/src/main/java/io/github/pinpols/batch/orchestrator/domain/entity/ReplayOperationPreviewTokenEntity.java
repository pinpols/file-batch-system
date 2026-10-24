package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

/** Hash-only, one-use preview credential for a replay operation. */
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
