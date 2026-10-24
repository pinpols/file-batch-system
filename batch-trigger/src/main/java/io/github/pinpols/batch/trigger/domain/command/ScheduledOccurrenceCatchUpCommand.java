package io.github.pinpols.batch.trigger.domain.command;

import io.github.pinpols.batch.trigger.web.request.ScheduledOccurrenceCatchUpRequest;

public record ScheduledOccurrenceCatchUpCommand(
    ScheduledOccurrenceCatchUpRequest request,
    String idempotencyKey,
    String requestId,
    String traceId) {}
