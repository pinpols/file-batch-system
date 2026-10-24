package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.Instant;
import java.time.LocalDate;

/** 安全的运维投影，不会公开已持久化的启动载荷。 */
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
