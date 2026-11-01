package io.github.pinpols.batch.console.domain.job.view;

import java.time.Instant;

public record BatchDayScheduledOccurrenceFireView(
    Long workflowDefinitionId,
    Instant scheduledFireTime,
    String fireInstanceId,
    Integer refireCount,
    Instant actualFireTime,
    String fireKind) {}
