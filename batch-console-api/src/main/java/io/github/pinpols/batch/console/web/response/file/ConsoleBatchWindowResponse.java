package io.github.pinpols.batch.console.web.response.file;

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
