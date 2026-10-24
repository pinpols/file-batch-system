package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;

public record ConsoleScheduledOccurrenceResponse(
    Long workflowDefinitionId,
    String workflowCode,
    String catchUpPolicy,
    Integer scheduleVersion,
    Instant scheduledFireTime,
    LocalDate bizDate,
    String occurrenceStatus,
    String requestId,
    String lastRejectionReason) {}
