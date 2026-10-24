package io.github.pinpols.batch.trigger.domain.command;

import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;

public record ExternalEventLaunchCommand(
    ExternalEventLaunchRequest request,
    String eventSource,
    String idempotencyKey,
    String requestId,
    String traceId) {}
