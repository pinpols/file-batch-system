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
    Boolean enabled) {}
