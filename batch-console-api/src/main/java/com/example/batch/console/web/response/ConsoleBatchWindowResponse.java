package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleBatchWindowResponse(
    Long id,
    String tenantId,
    String windowCode,
    String windowName,
    String timezone,
    String startTime,
    String endTime,
    String endStrategy,
    String outOfWindowAction,
    Boolean allowCrossDay,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
