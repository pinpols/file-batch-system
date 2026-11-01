package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;

public record ConsoleScheduledOccurrenceFireResponse(
    String fireInstanceId, Integer refireCount, Instant actualFireTime, String fireKind) {}
