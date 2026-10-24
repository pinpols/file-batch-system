package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.Instant;
import java.time.LocalDate;

/** Safe operational projection; never exposes the persisted launch payload. */
public record JobDependencyWaitingLaunchSummary(
    String tenantId,
    String requestId,
    String jobCode,
    LocalDate bizDate,
    String dependencySnapshot,
    String waitStatus,
    Integer attemptCount,
    String lastNotReadyReason,
    Instant lastCheckedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant timeoutAlertedAt) {}
