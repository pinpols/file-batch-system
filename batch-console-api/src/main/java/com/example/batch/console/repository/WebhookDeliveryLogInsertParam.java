package com.example.batch.console.repository;

import java.time.Instant;

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
