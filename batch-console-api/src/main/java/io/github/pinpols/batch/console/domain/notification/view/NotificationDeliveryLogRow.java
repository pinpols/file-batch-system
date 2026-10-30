package io.github.pinpols.batch.console.domain.notification.view;

import java.time.Instant;

/**
 * notification_delivery_log 行投影（NotificationDeliveryLogMapper.selectByTenant，固定 13 列，snake_case 键）。
 *
 * <p>payload_json / error_args 为 jsonb 列，按原始文本承载，响应层再做敏感值遮罩； alert_event_id 可空。
 */
public record NotificationDeliveryLogRow(
    Long id,
    String tenantId,
    Long ruleId,
    String channelCode,
    String eventType,
    Long alertEventId,
    String payloadJson,
    String deliveryStatus,
    String errorMessage,
    String errorKey,
    String errorArgs,
    Integer attempt,
    Instant createdAt) {}
