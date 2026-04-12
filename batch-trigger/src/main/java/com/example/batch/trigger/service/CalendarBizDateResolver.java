package com.example.batch.trigger.service;

import com.example.batch.trigger.support.CalendarBizDateDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CalendarBizDateResolver {

  private static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(6, 0);
  private static final int MAX_WORKDAY_SEARCH_DAYS = 365;

  public LocalDate resolve(
      Instant fireTime, ZoneId fallbackZoneId, CalendarBizDateDefinition calendar) {
    Objects.requireNonNull(fireTime, "fireTime");
    ZoneId zoneId = resolveZoneId(fallbackZoneId, calendar);
    if (calendar == null) {
      return fireTime.atZone(zoneId).toLocalDate();
    }
    LocalDate localDate = fireTime.atZone(zoneId).toLocalDate();
    LocalTime localTime = fireTime.atZone(zoneId).toLocalTime();
    LocalDate rawBizDate =
        localTime.isBefore(resolveCutoffTime(calendar)) ? localDate.minusDays(1) : localDate;
    return adjustForHoliday(rawBizDate, calendar);
  }

  private ZoneId resolveZoneId(ZoneId fallbackZoneId, CalendarBizDateDefinition calendar) {
    if (calendar != null && calendar.timezone() != null && !calendar.timezone().isBlank()) {
      return ZoneId.of(calendar.timezone());
    }
    return fallbackZoneId == null ? ZoneId.systemDefault() : fallbackZoneId;
  }

  private LocalTime resolveCutoffTime(CalendarBizDateDefinition calendar) {
    return calendar.cutoffTime() == null ? DEFAULT_CUTOFF_TIME : calendar.cutoffTime();
  }

  private LocalDate adjustForHoliday(LocalDate date, CalendarBizDateDefinition calendar) {
    if (!isHoliday(date, calendar)) {
      return date;
    }
    String rollRule = normalize(calendar.holidayRollRule());
    return switch (rollRule) {
      case "SKIP" -> null;
      case "PREV_WORKDAY" -> previousWorkday(date, calendar);
      case "NEXT_WORKDAY" -> nextWorkday(date, calendar);
      default -> date;
    };
  }

  private LocalDate previousWorkday(LocalDate date, CalendarBizDateDefinition calendar) {
    LocalDate candidate = date.minusDays(1);
    for (int i = 0; i < MAX_WORKDAY_SEARCH_DAYS; i++) {
      if (!isHoliday(candidate, calendar)) {
        return candidate;
      }
      candidate = candidate.minusDays(1);
    }
    throw new IllegalStateException(
        "No workday found within "
            + MAX_WORKDAY_SEARCH_DAYS
            + " days before "
            + date
            + " — calendar may have all days marked as holidays");
  }

  private LocalDate nextWorkday(LocalDate date, CalendarBizDateDefinition calendar) {
    LocalDate candidate = date.plusDays(1);
    for (int i = 0; i < MAX_WORKDAY_SEARCH_DAYS; i++) {
      if (!isHoliday(candidate, calendar)) {
        return candidate;
      }
      candidate = candidate.plusDays(1);
    }
    throw new IllegalStateException(
        "No workday found within "
            + MAX_WORKDAY_SEARCH_DAYS
            + " days after "
            + date
            + " — calendar may have all days marked as holidays");
  }

  private boolean isHoliday(LocalDate date, CalendarBizDateDefinition calendar) {
    Set<LocalDate> workdayOverrides = calendar.workdayOverrides();
    if (workdayOverrides != null && workdayOverrides.contains(date)) {
      return false;
    }
    Set<LocalDate> holidays = calendar.holidays();
    return holidays != null && holidays.contains(date);
  }

  private String normalize(String value) {
    return value == null ? "SKIP" : value.trim().toUpperCase(Locale.ROOT);
  }
}
