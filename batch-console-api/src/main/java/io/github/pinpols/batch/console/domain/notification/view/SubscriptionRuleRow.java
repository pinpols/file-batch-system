package io.github.pinpols.batch.console.domain.notification.view;

import java.time.Instant;

/**
 * batch.subscription_rule 行投影（SubscriptionRuleMapper.selectByTenant / selectById，固定 12 列）。
 */
public record SubscriptionRuleRow(
    Long id,
    String tenantId,
    String ruleName,
    String channelCode,
    String eventTypes,
    String severityFilter,
    String jobCodeFilter,
    Boolean enabled,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {}
