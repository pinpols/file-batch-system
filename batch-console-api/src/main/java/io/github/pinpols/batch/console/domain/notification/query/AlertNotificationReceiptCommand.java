package io.github.pinpols.batch.console.domain.notification.query;

/** 单条告警在指定接收渠道上的明确成功回执。 */
public record AlertNotificationReceiptCommand(
    String tenantId,
    String fingerprint,
    String alertType,
    String receiver,
    String notificationStatus) {}
