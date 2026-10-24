package io.github.pinpols.batch.console.domain.job.view;

import java.time.Instant;
import java.time.LocalDate;

/** 批次日窗口中已调度 occurrence 的固定列投影。 */
public record BatchDayScheduledOccurrenceView(
    Long workflowDefinitionId,
    String workflowCode,
    String catchUpPolicy,
    Integer scheduleVersion,
    Instant scheduledFireTime,
    LocalDate bizDate,
    String occurrenceStatus,
    String requestId,
    String lastRejectionReason) {}
