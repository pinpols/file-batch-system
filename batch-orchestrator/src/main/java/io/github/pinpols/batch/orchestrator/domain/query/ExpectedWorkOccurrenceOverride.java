package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.Instant;
import java.time.LocalDate;

/** Snapshot of an unresolved input obligation explicitly waived by a batch-day operator. */
public record ExpectedWorkOccurrenceOverride(
    Long id,
    Integer generation,
    Long workflowDefinitionId,
    String sourceType,
    String sourceKey,
    LocalDate bizDate,
    String occurrenceStatus,
    Instant expectedAt,
    Instant dueAt,
    String triggerRequestId) {}
