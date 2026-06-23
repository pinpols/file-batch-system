package io.github.pinpols.batch.console.domain.notification.service;

import java.time.Instant;

/**
 * Webhook 投递的标准化 payload 模型。
 *
 * <p>由 {@link WebhookDispatcher} 序列化为 JSON 写入 {@code webhook_delivery_log.payload_json},供 {@link
 * WebhookDeliveryRelay} 重投时反序列化复用同一份 body / 同一份 stream 头。
 */
public record WebhookEventPayload(
    String tenantId,
    String eventType,
    String stream,
    String cursor,
    Instant emittedAt,
    Object data) {}
