package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record JobDependencyWaitingLaunchEntity(
    Long id,
    String tenantId,
    String requestId,
    String jobCode,
    LocalDate bizDate,
    String dependencySnapshot,
    String launchPayload,
    String waitStatus,
    Integer attemptCount,
    String lastNotReadyReason,
    Instant lastCheckedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant releasedAt,
    Instant timeoutAlertedAt) {}
