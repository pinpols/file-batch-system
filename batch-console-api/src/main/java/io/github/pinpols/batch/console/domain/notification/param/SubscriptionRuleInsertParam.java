package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 订阅规则创建的固定写入参数。 */
@Builder
public record SubscriptionRuleInsertParam(
    String tenantId,
    String ruleName,
    String channelCode,
    String eventTypes,
    String severityFilter,
    String jobCodeFilter,
    Boolean enabled,
    String createdBy,
    String updatedBy) {}
