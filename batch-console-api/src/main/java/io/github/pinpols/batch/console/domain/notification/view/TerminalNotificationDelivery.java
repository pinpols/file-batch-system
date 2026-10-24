package io.github.pinpols.batch.console.domain.notification.view;

/** 首次消费 Workflow 终态事件时选定并持久化的接收方。 */
public record TerminalNotificationDelivery(
    String source,
    Long id,
    String tenantId,
    Long recipientId,
    String channelCode,
    String eventKey,
    String payloadJson,
    int attempt) {}
