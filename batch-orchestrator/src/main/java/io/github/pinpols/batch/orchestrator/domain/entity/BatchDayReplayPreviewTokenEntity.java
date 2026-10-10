package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;

/** 短时预览凭证的持久化投影；只保存凭证哈希，不保存返回给客户端的原值。 */
public record BatchDayReplayPreviewTokenEntity(
    String tenantId,
    String tokenHash,
    String requestHash,
    String snapshotHash,
    Instant expiresAt,
    Instant consumedAt,
    Long sessionId) {}
