package io.github.pinpols.batch.trigger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ExternalEventLaunchStatus(
    String requestId,
    String eventSource,
    String eventId,
    String eventCode,
    LocalDate bizDate,
    String traceId,
    List<ExternalEventTargetStatus> targets,
    Instant updatedAt) {}
