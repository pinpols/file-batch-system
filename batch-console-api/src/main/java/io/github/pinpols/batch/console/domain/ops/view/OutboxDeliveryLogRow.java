package io.github.pinpols.batch.console.domain.ops.view;

import java.time.Instant;

/**
 * batch.event_delivery_log 行投影（OutboxDeliveryLogMapper.selectByQuery，固定 13 列）。
 *
 * <p>error_key / error_args / error_message 在成功投递行中为 null，按可空 String 承载。
 */
public record OutboxDeliveryLogRow(
    Long id,
    String tenantId,
    String eventType,
    String eventKey,
    String traceId,
    String deliveryStatus,
    String targetTopic,
    Integer deliveryAttempt,
    String errorKey,
    String errorArgs,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {}
