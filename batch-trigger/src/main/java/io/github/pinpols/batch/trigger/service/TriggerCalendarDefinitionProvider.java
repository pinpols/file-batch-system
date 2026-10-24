package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.trigger.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;
import io.github.pinpols.batch.trigger.support.CalendarHolidayRule;
import io.github.pinpols.batch.trigger.support.TriggerCalendarConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 在报告事务中串行化终态条目的记账操作。 */
@Service
@RequiredArgsConstructor
public class TriggerCalendarDefinitionProvider {

  private final BusinessCalendarMapper businessCalendarMapper;

  public CalendarBizDateDefinition load(String tenantId, String rawCalendarCode) {
    String calendarCode = CodeNormalizer.toConfigFormOrNull(rawCalendarCode);
    if (!Texts.hasText(calendarCode)) {
      return null;
    }
    TriggerCalendarConfig calendar =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
    if (EmptyChecks.isNull(calendar) || EmptyChecks.isNull(calendar.getId())) {
      throw BizException.of(
          ResultCode.NOT_FOUND,
          "error.trigger.business_calendar_not_found",
          LogSanitizer.value(tenantId),
          LogSanitizer.value(calendarCode));
    }
    List<CalendarHolidayRule> rules =
        businessCalendarMapper.selectHolidayRulesByCalendarId(calendar.getId());
    if (EmptyChecks.isNull(rules)) {
      rules = List.of();
    }
    Set<LocalDate> holidays = rules.stream()
        .filter(rule -> isDayType(rule, "HOLIDAY"))
        .map(CalendarHolidayRule::getBizDate)
        .collect(Collectors.toUnmodifiableSet());
    Set<LocalDate> workdayOverrides = rules.stream()
        .filter(rule -> isDayType(rule, "WORKDAY_OVERRIDE"))
        .map(CalendarHolidayRule::getBizDate)
        .collect(Collectors.toUnmodifiableSet());
    CalendarBizDateDefinition definition = new CalendarBizDateDefinition(
        calendar.getTimezone(),
        calendar.getCutoffTime(),
        calendar.getHolidayRollRule(),
        holidays,
        workdayOverrides);
    return definition;
  }

  private boolean isDayType(CalendarHolidayRule rule, String expectedType) {
    boolean ruleExists = EmptyChecks.isNotNull(rule);
    if (!ruleExists) {
      return false;
    }
    boolean hasBusinessDate = EmptyChecks.isNotNull(rule.getBizDate());
    String normalizedDayType = normalize(rule.getDayType());
    boolean matchesExpectedType = expectedType.equalsIgnoreCase(normalizedDayType);
    return hasBusinessDate && matchesExpectedType;
  }

  private String normalize(String value) {
    if (EmptyChecks.isNull(value)) {
      return "";
    }
    String trimmedValue = value.trim();
    return trimmedValue.toUpperCase(Locale.ROOT);
  }
}
