package com.example.batch.orchestrator.domain.entity;

import java.time.LocalTime;

public record BusinessCalendarEntity(
    Long id,
    String tenantId,
    String calendarCode,
    String calendarName,
    String timezone,
    String holidayRollRule,
    String catchUpPolicy,
    Integer catchUpMaxDays,
    LocalTime cutoffTime,
    Integer lateArrivalToleranceMin,
    Integer slaOffsetMin,
    String dayRolloverPolicy,
    String dstGapPolicy,
    String dstOverlapPolicy,
    Boolean enabled) {

  public BusinessCalendarEntity(
      Long id,
      String tenantId,
      String calendarCode,
      String calendarName,
      String timezone,
      String holidayRollRule,
      String catchUpPolicy,
      Integer catchUpMaxDays,
      LocalTime cutoffTime,
      Integer lateArrivalToleranceMin,
      Integer slaOffsetMin,
      Boolean enabled) {
    this(
        id,
        tenantId,
        calendarCode,
        calendarName,
        timezone,
        holidayRollRule,
        catchUpPolicy,
        catchUpMaxDays,
        cutoffTime,
        lateArrivalToleranceMin,
        slaOffsetMin,
        "ALLOW_OVERLAP",
        "RUN_AT_NEXT_VALID_TIME",
        "RUN_ONCE_EARLIER_OFFSET",
        enabled);
  }

  public BusinessCalendarEntity(
      Long id,
      String tenantId,
      String calendarCode,
      String calendarName,
      String timezone,
      String holidayRollRule,
      String catchUpPolicy,
      Integer catchUpMaxDays,
      LocalTime cutoffTime,
      Integer lateArrivalToleranceMin,
      Integer slaOffsetMin,
      String dayRolloverPolicy,
      Boolean enabled) {
    this(
        id,
        tenantId,
        calendarCode,
        calendarName,
        timezone,
        holidayRollRule,
        catchUpPolicy,
        catchUpMaxDays,
        cutoffTime,
        lateArrivalToleranceMin,
        slaOffsetMin,
        dayRolloverPolicy,
        "RUN_AT_NEXT_VALID_TIME",
        "RUN_ONCE_EARLIER_OFFSET",
        enabled);
  }
}
