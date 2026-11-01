package io.github.pinpols.batch.trigger.domain.command;

public record TriggerLaunchCommand(
    TriggerLaunchInput request, String idempotencyKey, String requestId, String traceId) {}
