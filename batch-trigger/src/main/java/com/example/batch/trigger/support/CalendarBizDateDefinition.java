package com.example.batch.trigger.support;

import java.time.LocalTime;
import java.util.Set;

public record CalendarBizDateDefinition(
        String timezone,
        LocalTime cutoffTime,
        String holidayRollRule,
        Set<java.time.LocalDate> holidays,
        Set<java.time.LocalDate> workdayOverrides
) {
}
