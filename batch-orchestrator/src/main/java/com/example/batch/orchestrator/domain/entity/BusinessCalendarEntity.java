package com.example.batch.orchestrator.domain.entity;

import java.time.LocalTime;
import lombok.Builder;

@Builder(toBuilder = true)
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
    /** ADR-023 加入哪个 calendar_group.group_code；NULL = 不加入。 */
    String groupCode,
    /** ADR-023 半天 / 多 cutoff JSONB schedule；NULL = 用 cutoffTime 单值。 */
    String cutoffSchedule,
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
        null,
        null,
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
        null,
        null,
        enabled);
  }

  /** 16-arg 兼容构造：DST 显式但未带 group/cutoff schedule。 */
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
      String dstGapPolicy,
      String dstOverlapPolicy,
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
        dstGapPolicy,
        dstOverlapPolicy,
        null,
        null,
        enabled);
  }
}
