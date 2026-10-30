package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 通知渠道创建及软删除恢复的固定写入参数。 */
@Builder
public record NotificationChannelInsertParam(
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    String configJson,
    Boolean enabled,
    String createdBy,
    String updatedBy) {}
