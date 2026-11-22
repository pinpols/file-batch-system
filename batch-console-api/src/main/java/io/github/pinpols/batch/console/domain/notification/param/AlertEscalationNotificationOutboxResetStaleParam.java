package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 绑定一次告警通知 Outbox 超时复位；claimToken 限定只回收扫描时看到的同一轮领取。 */
@Builder
public record AlertEscalationNotificationOutboxResetStaleParam(
    Long id,
    String tenantId,
    String failedStatus,
    String publishingStatus,
    String claimToken,
    long timeoutSeconds) {}
