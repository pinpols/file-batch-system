package io.github.pinpols.batch.console.shared.view;

import lombok.Builder;

import java.time.Instant;
@Builder
public record ConsoleOutboxDeliveryLogResponse(
    Long id,
    String tenantId,
    String eventType,
    String eventKey,
    String traceId,
    String deliveryStatus,
    String targetTopic,
    Integer deliveryAttempt,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {}
