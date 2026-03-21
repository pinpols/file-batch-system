package com.example.batch.common.utils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class DateUtils {

    private static final Clock CLOCK = Clock.systemDefaultZone();

    private DateUtils() {
    }

    public static LocalDate today() {
        return LocalDate.now(CLOCK);
    }

    public static Instant now() {
        return Instant.now(CLOCK);
    }

    public static ZoneId zoneId() {
        return CLOCK.getZone();
    }
}
