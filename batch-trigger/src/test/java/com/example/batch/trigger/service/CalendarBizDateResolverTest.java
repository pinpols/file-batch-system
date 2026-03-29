package com.example.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.trigger.support.CalendarBizDateDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CalendarBizDateResolverTest {

    private final CalendarBizDateResolver resolver = new CalendarBizDateResolver();

    @Test
    void shouldUsePreviousBusinessDayWhenTriggeredBeforeCutoff() {
        LocalDate bizDate = resolver.resolve(
                instant("2026-03-29T02:00:00+08:00"),
                ZoneId.of("Asia/Shanghai"),
                calendar("Asia/Shanghai", "SKIP", Set.of(), Set.of())
        );

        assertThat(bizDate).isEqualTo(LocalDate.of(2026, 3, 28));
    }

    @Test
    void shouldUseSameBusinessDayWhenTriggeredAfterCutoff() {
        LocalDate bizDate = resolver.resolve(
                instant("2026-03-29T08:00:00+08:00"),
                ZoneId.of("Asia/Shanghai"),
                calendar("Asia/Shanghai", "SKIP", Set.of(), Set.of())
        );

        assertThat(bizDate).isEqualTo(LocalDate.of(2026, 3, 29));
    }

    @Test
    void shouldSkipWhenPreviousBusinessDayIsHolidayAndRuleIsSkip() {
        LocalDate bizDate = resolver.resolve(
                instant("2026-03-29T02:00:00+08:00"),
                ZoneId.of("Asia/Shanghai"),
                calendar("Asia/Shanghai", "SKIP", Set.of(LocalDate.of(2026, 3, 28)), Set.of())
        );

        assertThat(bizDate).isNull();
    }

    @Test
    void shouldMoveToPreviousWorkdayWhenHolidayRuleRequiresIt() {
        LocalDate bizDate = resolver.resolve(
                instant("2026-03-29T02:00:00+08:00"),
                ZoneId.of("Asia/Shanghai"),
                calendar("Asia/Shanghai", "PREV_WORKDAY", Set.of(LocalDate.of(2026, 3, 28)), Set.of())
        );

        assertThat(bizDate).isEqualTo(LocalDate.of(2026, 3, 27));
    }

    @Test
    void shouldFallBackToOriginalTimezoneBasedLogicWhenCalendarIsMissing() {
        LocalDate bizDate = resolver.resolve(
                instant("2026-03-27T16:30:00Z"),
                ZoneId.of("Asia/Shanghai"),
                null
        );

        assertThat(bizDate).isEqualTo(LocalDate.of(2026, 3, 28));
    }

    private CalendarBizDateDefinition calendar(String timezone,
                                               String holidayRollRule,
                                               Set<LocalDate> holidays,
                                               Set<LocalDate> workdayOverrides) {
        return new CalendarBizDateDefinition(
                timezone,
                LocalTime.of(6, 0),
                holidayRollRule,
                holidays,
                workdayOverrides
        );
    }

    private Instant instant(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }
}
