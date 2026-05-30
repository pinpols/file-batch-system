package com.example.batch.console.domain.ops.web.response;

import java.time.Instant;

public record ConsoleOutboxDeliveryLogResponse(
    Long id,
    String tenantId,
    String eventType,
    String eventKey,
    String deliveryStatus,
    String targetTopic,
    Integer deliveryAttempt,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {}
