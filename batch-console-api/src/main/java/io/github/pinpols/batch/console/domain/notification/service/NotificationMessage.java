package io.github.pinpols.batch.console.domain.notification.service;

/**
 * 单条待投递通知消息（渠道无关）。{@code configJson} 为该渠道 {@code notification_channel.config_json} 原文（各 sender
 * 自取所需键，如 bot url / secret / 收件人）；{@code payload} 为结构化事件、{@code payloadJson} 为其渲染 JSON。
 */
public record NotificationMessage(
    String tenantId,
    String channelCode,
    String channelType,
    String configJson,
    WebhookEventPayload payload,
    String payloadJson) {}
