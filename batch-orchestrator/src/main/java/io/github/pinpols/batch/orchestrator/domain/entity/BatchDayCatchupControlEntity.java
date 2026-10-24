package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;

/** Current operator-controlled pause state for one tenant calendar's automatic catch-up. */
public record BatchDayCatchupControlEntity(
    String tenantId,
    String calendarCode,
    Boolean paused,
    Instant pausedAt,
    String pausedBy,
    String pauseReason,
    Instant updatedAt) {}
