package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 持久化的跨日历依赖等待，业务日期和日历快照在首次等待时固定。 */
@Builder(toBuilder = true)
public record CalendarDependencyWaitEntity(
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

  public static final String STATUS_WAITING = "WAITING";
  public static final String STATUS_OPENED = "OPENED";
  public static final String STATUS_SKIPPED = "SKIPPED";
  public static final String MANUAL_ACTION_RELEASE = "RELEASE";
  public static final String MANUAL_ACTION_SKIP = "SKIP";
}
