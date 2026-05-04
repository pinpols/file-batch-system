package com.example.batch.orchestrator.domain.scheduling;

import com.example.batch.common.logging.SwallowedExceptionLogger;
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
      return QuotaResetPolicy.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      SwallowedExceptionLogger.info(QuotaResetPolicy.class, "catch:IllegalArgumentException", ex);

      return NONE;
    }
  }

  public boolean isRuntimeManaged() {
    return this == CALENDAR_DAY || this == SLIDING_WINDOW;
  }

  public static ZonedDateTime startOfCalendarDay(ZonedDateTime dateTime) {
    return dateTime.toLocalDate().atStartOfDay(dateTime.getZone());
  }
}
