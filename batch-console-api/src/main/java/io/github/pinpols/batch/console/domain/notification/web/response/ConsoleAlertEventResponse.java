package io.github.pinpols.batch.console.domain.notification.web.response;

import java.time.Instant;

public record ConsoleAlertEventResponse(
    Long id,
    String tenantId,
    String serviceName,
    String alertType,
    String severity,
    String title,
    String detailJson,
    String dedupFingerprint,
    Integer occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt,
    String traceId,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
