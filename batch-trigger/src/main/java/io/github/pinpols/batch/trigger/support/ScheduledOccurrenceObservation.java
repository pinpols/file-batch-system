package io.github.pinpols.batch.trigger.support;

import java.time.LocalDate;

/** 已观察 fire 的原始事实；重建回调时不能用当前日历覆盖。 */
public record ScheduledOccurrenceObservation(
    Integer scheduleVersion,
    String calendarCode,
    LocalDate bizDate,
    String status,
    String requestId) {}
