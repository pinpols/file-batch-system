package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 绑定一次告警通知 Outbox 发布成功回写；claimToken 为本轮领取凭据。 */
@Builder
public record AlertEscalationNotificationOutboxMarkPublishedParam(
    Long id, String tenantId, String publishedStatus, String publishingStatus, String claimToken) {}
