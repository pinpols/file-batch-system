package io.github.pinpols.batch.trigger.domain.query;

import java.time.Instant;

/** A bounded read of unobserved fires within one tenant, Workflow and schedule version. */
public record UnobservedPlannedOccurrenceQuery(
    String tenantId,
    Long workflowDefinitionId,
    int scheduleVersion,
    Instant beforeExclusive,
    int limit) {}
