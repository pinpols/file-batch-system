package com.example.batch.trigger.support;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public record CalendarBizDateDefinition(
        String timezone,
        LocalTime cutoffTime,
        String holidayRollRule,
        Set<LocalDate> holidays,
        Set<LocalDate> workdayOverrides
) {
}
