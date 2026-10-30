package io.github.pinpols.batch.console.domain.audit.query;

import lombok.Builder;

/** 绑定一次selectTurns操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleAiConversationTurnsQuery(
    String tenantId, String conversationId, String ownerUserId, Long beforeTurnNo, int limit) {}
