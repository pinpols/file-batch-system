package io.github.pinpols.batch.console.domain.notification.view;

import java.time.Instant;

/**
 * batch.notification_channel 行投影（NotificationChannelMapper.selectByTenant / selectByCode，固定 11 列）。
 *
 * <p>config_json 为 jsonb 列，按文本形态承载，消费方按需 JsonUtils 解析。
 */
public record NotificationChannelRow(
    Long id,
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    String configJson,
    Boolean enabled,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {}
