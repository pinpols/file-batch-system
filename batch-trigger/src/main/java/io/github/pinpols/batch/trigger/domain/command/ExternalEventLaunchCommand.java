package io.github.pinpols.batch.trigger.domain.command;

public record ExternalEventLaunchCommand(
    ExternalEventLaunchInput request,
    String eventSource,
    String idempotencyKey,
    String requestId,
    String traceId) {}
