package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 通知渠道更新的固定写入参数。 */
@Builder
public record NotificationChannelUpdateParam(
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    String configJson,
    Boolean enabled,
    String updatedBy) {}
