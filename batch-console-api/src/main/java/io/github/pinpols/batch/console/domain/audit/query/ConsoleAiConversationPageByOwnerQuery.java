package io.github.pinpols.batch.console.domain.audit.query;

import lombok.Builder;

import java.time.Instant;
/** 绑定一次selectPageByOwner操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleAiConversationPageByOwnerQuery(
    String tenantId,
    String ownerUserId,
    Instant beforeUpdatedAt,
    String beforeId,
    int limit) {}
