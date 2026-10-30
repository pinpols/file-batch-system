package io.github.pinpols.batch.console.domain.audit.param;

import java.time.OffsetDateTime;
import lombok.Builder;

/** 绑定一次allocateTurnNo操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleAiConversationAllocateTurnNoParam(
    String tenantId,
    String conversationId,
    String ownerUserId,
    OffsetDateTime expiresAt,
    String contextVersion) {}
