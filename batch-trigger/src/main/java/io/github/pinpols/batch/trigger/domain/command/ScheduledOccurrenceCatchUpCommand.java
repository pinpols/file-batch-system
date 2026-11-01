package io.github.pinpols.batch.trigger.domain.command;

public record ScheduledOccurrenceCatchUpCommand(
    ScheduledOccurrenceCatchUpInput request,
    String idempotencyKey,
    String requestId,
    String traceId) {}
