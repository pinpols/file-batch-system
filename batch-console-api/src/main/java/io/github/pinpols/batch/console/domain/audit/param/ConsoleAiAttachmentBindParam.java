package io.github.pinpols.batch.console.domain.audit.param;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/** 绑定一次bind操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleAiAttachmentBindParam(
    String tenantId,
    UUID id,
    String ownerUserId,
    String conversationId,
    long turnNo,
    Instant expiresAt) {}
