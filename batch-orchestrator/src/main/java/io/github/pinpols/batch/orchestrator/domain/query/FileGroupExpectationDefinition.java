package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.LocalTime;

public record FileGroupExpectationDefinition(
    String tenantId,
    String calendarCode,
    Long workflowDefinitionId,
    String fileGroupCode,
    LocalTime expectedLocalTime,
    LocalTime dueLocalTime) {}
