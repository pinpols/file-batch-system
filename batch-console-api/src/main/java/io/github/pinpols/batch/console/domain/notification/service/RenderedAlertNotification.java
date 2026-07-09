package io.github.pinpols.batch.console.domain.notification.service;

import java.util.Map;

/**
 * Alertmanager 告警渲染结果:{@code title} 人类可读标题(填入 {@link WebhookEventPayload#eventType}),{@code body}
 * 人类可读正文,{@code structured} 结构化字段(序列化进 {@code notification_delivery_log.payload_json},须为合法 JSON
 * 对象)。
 */
public record RenderedAlertNotification(
    String title, String body, Map<String, Object> structured) {}
