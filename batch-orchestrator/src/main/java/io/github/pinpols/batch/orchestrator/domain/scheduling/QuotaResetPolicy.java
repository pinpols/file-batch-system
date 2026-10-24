package io.github.pinpols.batch.orchestrator.domain.scheduling;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public enum QuotaResetPolicy {
  NONE,
  CALENDAR_DAY,
  SLIDING_WINDOW;

  public static QuotaResetPolicy from(String value) {
    if (value == null || value.isBlank()) {
      return NONE;
    }
    try {
      String trimmedValue = value.trim();
      String normalizedValue = trimmedValue.toUpperCase();
      return QuotaResetPolicy.valueOf(normalizedValue);
    } catch (IllegalArgumentException ex) {
      SwallowedExceptionLogger.info(QuotaResetPolicy.class, "catch:IllegalArgumentException", ex);

      return NONE;
    }
  }

  public boolean isRuntimeManaged() {
    return this == CALENDAR_DAY || this == SLIDING_WINDOW;
  }

  public static ZonedDateTime startOfCalendarDay(ZonedDateTime dateTime) {
    LocalDate calendarDate = dateTime.toLocalDate();
    ZoneId timeZone = dateTime.getZone();
    return calendarDate.atStartOfDay(timeZone);
  }
}
