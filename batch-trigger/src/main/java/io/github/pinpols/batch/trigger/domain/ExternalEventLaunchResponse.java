package io.github.pinpols.batch.trigger.domain;

import java.util.List;

public record ExternalEventLaunchResponse(
    String requestId, String eventId, String traceId, List<ExternalEventLaunchTarget> targets) {}
