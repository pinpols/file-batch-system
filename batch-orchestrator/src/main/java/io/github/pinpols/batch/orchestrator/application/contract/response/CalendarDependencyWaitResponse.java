package io.github.pinpols.batch.orchestrator.application.contract.response;

import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import java.time.Instant;
import java.time.LocalDate;

/** HTTP representation of one cross-calendar dependency wait. */
public record CalendarDependencyWaitResponse(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String waitStatus,
    String blockedReason,
    Instant firstWaitingAt,
    Instant lastCheckedAt,
    Integer attemptCount,
    Instant cutoffAt,
    Instant slaDeadlineAt,
    String timezoneSnapshot,
    String dstPolicySnapshot,
    Instant openedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant timeoutAlertedAt,
    String dependencySnapshot,
    String manualAction,
    String manualOperatorId,
    String manualReason,
    Instant manualRequestedAt) {

  public static CalendarDependencyWaitResponse from(CalendarDependencyWaitEntity entity) {
    return new CalendarDependencyWaitResponse(
        entity.id(),
        entity.tenantId(),
        entity.calendarCode(),
        entity.bizDate(),
        entity.waitStatus(),
        entity.blockedReason(),
        entity.firstWaitingAt(),
        entity.lastCheckedAt(),
        entity.attemptCount(),
        entity.cutoffAt(),
        entity.slaDeadlineAt(),
        entity.timezoneSnapshot(),
        entity.dstPolicySnapshot(),
        entity.openedAt(),
        entity.createdAt(),
        entity.updatedAt(),
        entity.timeoutAlertedAt(),
        entity.dependencySnapshot(),
        entity.manualAction(),
        entity.manualOperatorId(),
        entity.manualReason(),
        entity.manualRequestedAt());
  }
}
