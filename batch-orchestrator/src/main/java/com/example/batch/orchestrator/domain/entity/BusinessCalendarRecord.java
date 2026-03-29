package com.example.batch.orchestrator.domain.entity;

import java.time.LocalTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "business_calendar")
public record BusinessCalendarRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("calendar_code") String calendarCode,
        @Column("calendar_name") String calendarName,
        @Column("timezone") String timezone,
        @Column("holiday_roll_rule") String holidayRollRule,
        @Column("catch_up_policy") String catchUpPolicy,
        @Column("catch_up_max_days") Integer catchUpMaxDays,
        @Column("cutoff_time") LocalTime cutoffTime,
        @Column("late_arrival_tolerance_min") Integer lateArrivalToleranceMin,
        @Column("sla_offset_min") Integer slaOffsetMin,
        @Column("enabled") Boolean enabled
) {}
