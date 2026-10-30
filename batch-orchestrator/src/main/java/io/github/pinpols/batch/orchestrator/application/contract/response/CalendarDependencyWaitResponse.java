package io.github.pinpols.batch.orchestrator.application.contract.response;

import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 单条跨日历依赖等待记录的 HTTP 表示。 */
@Builder
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
    return CalendarDependencyWaitResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .calendarCode(entity.calendarCode())
        .bizDate(entity.bizDate())
        .waitStatus(entity.waitStatus())
        .blockedReason(entity.blockedReason())
        .firstWaitingAt(entity.firstWaitingAt())
        .lastCheckedAt(entity.lastCheckedAt())
        .attemptCount(entity.attemptCount())
        .cutoffAt(entity.cutoffAt())
        .slaDeadlineAt(entity.slaDeadlineAt())
        .timezoneSnapshot(entity.timezoneSnapshot())
        .dstPolicySnapshot(entity.dstPolicySnapshot())
        .openedAt(entity.openedAt())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .timeoutAlertedAt(entity.timeoutAlertedAt())
        .dependencySnapshot(entity.dependencySnapshot())
        .manualAction(entity.manualAction())
        .manualOperatorId(entity.manualOperatorId())
        .manualReason(entity.manualReason())
        .manualRequestedAt(entity.manualRequestedAt())
        .build();
  }
}
