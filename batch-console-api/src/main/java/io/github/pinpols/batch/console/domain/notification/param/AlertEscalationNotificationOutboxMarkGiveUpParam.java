package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 绑定一次markGiveUp操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record AlertEscalationNotificationOutboxMarkGiveUpParam(
    Long id, String tenantId, String giveUpStatus, String lastError, String publishingStatus) {}
