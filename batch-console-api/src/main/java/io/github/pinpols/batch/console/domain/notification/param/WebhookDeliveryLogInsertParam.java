package io.github.pinpols.batch.console.domain.notification.param;

import lombok.Builder;

import java.time.Instant;
@Builder
public record WebhookDeliveryLogInsertParam(
    String tenantId,
    Long subscriptionId,
    String eventType,
    String payloadJson,
    Integer httpStatus,
    String responseBody,
    String deliveryStatus,
    int attempt,
    Instant nextRetryAt) {}
