package com.example.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class QuotaResetPolicyTest {

    // ── from() ────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnNoneWhenValueIsNull() {
        assertThat(QuotaResetPolicy.from(null)).isEqualTo(QuotaResetPolicy.NONE);
    }

    @Test
    void shouldReturnNoneWhenValueIsBlank() {
        assertThat(QuotaResetPolicy.from("  ")).isEqualTo(QuotaResetPolicy.NONE);
    }

    @Test
    void shouldReturnNoneWhenValueIsUnknown() {
        assertThat(QuotaResetPolicy.from("UNKNOWN_POLICY")).isEqualTo(QuotaResetPolicy.NONE);
    }

    @Test
    void shouldParseCasedCalendarDay() {
        assertThat(QuotaResetPolicy.from("calendar_day")).isEqualTo(QuotaResetPolicy.CALENDAR_DAY);
        assertThat(QuotaResetPolicy.from("CALENDAR_DAY")).isEqualTo(QuotaResetPolicy.CALENDAR_DAY);
        assertThat(QuotaResetPolicy.from("Calendar_Day")).isEqualTo(QuotaResetPolicy.CALENDAR_DAY);
    }

    @Test
    void shouldParseSlidingWindow() {
        assertThat(QuotaResetPolicy.from("SLIDING_WINDOW")).isEqualTo(QuotaResetPolicy.SLIDING_WINDOW);
        assertThat(QuotaResetPolicy.from("sliding_window")).isEqualTo(QuotaResetPolicy.SLIDING_WINDOW);
    }

    @Test
    void shouldParseNoneExplicitly() {
        assertThat(QuotaResetPolicy.from("NONE")).isEqualTo(QuotaResetPolicy.NONE);
        assertThat(QuotaResetPolicy.from("none")).isEqualTo(QuotaResetPolicy.NONE);
    }

    // ── isRuntimeManaged() ────────────────────────────────────────────────────

    @Test
    void noneIsNotRuntimeManaged() {
        assertThat(QuotaResetPolicy.NONE.isRuntimeManaged()).isFalse();
    }

    @Test
    void calendarDayIsRuntimeManaged() {
        assertThat(QuotaResetPolicy.CALENDAR_DAY.isRuntimeManaged()).isTrue();
    }

    @Test
    void slidingWindowIsRuntimeManaged() {
        assertThat(QuotaResetPolicy.SLIDING_WINDOW.isRuntimeManaged()).isTrue();
    }

    // ── startOfCalendarDay() ──────────────────────────────────────────────────

    @Test
    void shouldReturnMidnightOfSameDay() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime noon = ZonedDateTime.of(2026, 3, 22, 12, 30, 0, 0, zone);

        ZonedDateTime startOfDay = QuotaResetPolicy.startOfCalendarDay(noon);

        assertThat(startOfDay.getHour()).isZero();
        assertThat(startOfDay.getMinute()).isZero();
        assertThat(startOfDay.getSecond()).isZero();
        assertThat(startOfDay.getYear()).isEqualTo(2026);
        assertThat(startOfDay.getMonthValue()).isEqualTo(3);
        assertThat(startOfDay.getDayOfMonth()).isEqualTo(22);
    }

    @Test
    void startOfCalendarDayPreservesZone() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime dt = ZonedDateTime.of(2026, 6, 15, 18, 45, 0, 0, zone);

        ZonedDateTime result = QuotaResetPolicy.startOfCalendarDay(dt);

        assertThat(result.getZone()).isEqualTo(zone);
    }

    // ── systemZone() ──────────────────────────────────────────────────────────

    @Test
    void systemZoneShouldReturnNonNullZoneId() {
        assertThat(QuotaResetPolicy.systemZone()).isNotNull();
        assertThat(QuotaResetPolicy.systemZone()).isEqualTo(ZoneId.systemDefault());
    }
}
