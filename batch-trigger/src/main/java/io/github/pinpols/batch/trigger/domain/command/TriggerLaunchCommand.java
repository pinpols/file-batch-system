package io.github.pinpols.batch.trigger.domain.command;

import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;

public record TriggerLaunchCommand(
    TriggerLaunchRequest request, String idempotencyKey, String requestId, String traceId) {}
