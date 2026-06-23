package io.github.pinpols.batch.console.domain.ops.web.response;

import java.time.Instant;

public record ConsoleOutboxRetryLogResponse(
    Long id,
    String tenantId,
    String eventType,
    String eventKey,
    String retryStatus,
    Integer retryCount,
    String retryPolicy,
    Instant nextRetryAt,
    Instant createdAt,
    Instant updatedAt) {}
