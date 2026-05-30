package com.example.batch.console.domain.notification.param;

import java.time.Instant;
import lombok.Builder;

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
