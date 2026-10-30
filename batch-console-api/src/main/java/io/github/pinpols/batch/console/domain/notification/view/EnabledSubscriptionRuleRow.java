package io.github.pinpols.batch.console.domain.notification.view;

/**
 * 启用订阅规则 + 渠道连接投影（SubscriptionRuleMapper.selectEnabledByEventType，固定 9 列）。
 *
 * <p>config_json 为 notification_channel 的 jsonb 列，按文本形态承载。
 */
public record EnabledSubscriptionRuleRow(
    Long id,
    String tenantId,
    String ruleName,
    String channelCode,
    String eventTypes,
    String severityFilter,
    String jobCodeFilter,
    String channelType,
    String configJson) {}
