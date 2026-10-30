package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

/** 订阅规则更新的固定写入参数。 */
@Builder
public record SubscriptionRuleUpdateParam(
    String tenantId,
    Long id,
    String ruleName,
    String channelCode,
    String eventTypes,
    String severityFilter,
    String jobCodeFilter,
    Boolean enabled,
    String updatedBy) {}
