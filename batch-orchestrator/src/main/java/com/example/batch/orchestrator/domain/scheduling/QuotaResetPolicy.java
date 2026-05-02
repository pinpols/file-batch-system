package com.example.batch.orchestrator.domain.scheduling;

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
      return QuotaResetPolicy.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return NONE;
    }
  }

  public boolean isRuntimeManaged() {
    return this == CALENDAR_DAY || this == SLIDING_WINDOW;
  }

  public static ZonedDateTime startOfCalendarDay(ZonedDateTime dateTime) {
    return dateTime.toLocalDate().atStartOfDay(dateTime.getZone());
  }

  /**
   * @deprecated 业务代码改用 {@code BatchTimezoneProvider.defaultZone()}；此方法仅遗留给旧测试。 {@link
   *     java.time.ZoneId#systemDefault()} 会随容器 TZ 漂移，新代码禁止使用。
   */
  @Deprecated(since = "2026-04-20", forRemoval = false)
  public static ZoneId systemZone() {
    return ZoneId.systemDefault();
  }
}
