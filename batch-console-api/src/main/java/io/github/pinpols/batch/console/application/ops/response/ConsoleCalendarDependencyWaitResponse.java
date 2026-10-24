package io.github.pinpols.batch.console.application.ops.response;

import java.time.Instant;
import java.time.LocalDate;

/** Console 对已持久化跨日历依赖等待记录的只读模型。 */
public record ConsoleCalendarDependencyWaitResponse(
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
    Instant timeoutAlertedAt,
    String dependencySnapshot,
    String manualAction,
    String manualOperatorId,
    String manualReason,
    Instant manualRequestedAt) {}
