package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.LocalTime;

public record ExternalEventExpectationDefinition(
    String tenantId,
    String calendarCode,
    Long workflowDefinitionId,
    String eventCode,
    LocalTime expectedLocalTime,
    LocalTime dueLocalTime) {}
