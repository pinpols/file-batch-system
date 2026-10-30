package io.github.pinpols.batch.console.shared.view;

import java.time.Instant;
import lombok.Builder;

@Builder
public record ConsoleOutboxRetryLogResponse(
    Long id,
    Long outboxEventId,
    String tenantId,
    String eventType,
    String eventKey,
    String retryStatus,
    Integer retryCount,
    String retryPolicy,
    Instant nextRetryAt,
    Instant createdAt,
    Instant updatedAt) {}
