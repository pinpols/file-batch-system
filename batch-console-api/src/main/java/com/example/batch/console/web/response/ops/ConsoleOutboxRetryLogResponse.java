package com.example.batch.console.web.response.ops;

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
