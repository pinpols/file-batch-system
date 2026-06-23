package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.normalize;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalBoolean;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireInteger;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireText;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.resolveTenantField;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.BusinessCalendar.COL_CALENDAR_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_MAX_DAYS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAYS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAY_ROLL_RULE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.BusinessCalendar.COL_TIMEZONE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CALENDAR_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DESCRIPTION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_ENABLED;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.HolidayRollRule;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpsertParam;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;

/** Shared parser/upsert helper for business_calendar Excel rows. */
public final class BusinessCalendarExcelRowParser {

  public static final String SHEET_NAME = ConfigPackageExcelSchema.BusinessCalendar.SHEET_NAME;

  private static final Set<String> HOLIDAY_ROLL_RULES = DictEnum.codes(HolidayRollRule.class);
  private static final Set<String> CATCH_UP_POLICIES = DictEnum.codes(CatchUpPolicyType.class);

  private BusinessCalendarExcelRowParser() {}

  public static CalendarRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    CalendarRow row =
        CalendarRow.builder()
            .rowNo(rowNo)
            .tenantId(effectiveTenant)
            .calendarCode(requireText(values, COL_CALENDAR_CODE, 128, issues))
            .calendarName(requireText(values, COL_CALENDAR_NAME, 256, issues))
            .timezone(requireText(values, COL_TIMEZONE, 64, issues))
            .holidayRollRule(
                optionalEnum(values, COL_HOLIDAY_ROLL_RULE, HOLIDAY_ROLL_RULES, 32, "SKIP", issues))
            .catchUpPolicy(
                optionalEnum(values, COL_CATCH_UP_POLICY, CATCH_UP_POLICIES, 32, "NONE", issues))
            .catchUpMaxDays(requireInteger(values, COL_CATCH_UP_MAX_DAYS, 0, issues))
            .holidays(parseHolidays(values.get(COL_HOLIDAYS), issues))
            .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
            .description(normalize(values.get(COL_DESCRIPTION)))
            .build();
    return row;
  }

  public static BusinessCalendarUpsertParam toUpsertParam(CalendarRow row, String operatorId) {
    BusinessCalendarUpsertParam param = new BusinessCalendarUpsertParam();
    param.setTenantId(row.tenantId());
    param.setCalendarCode(row.calendarCode());
    param.setCalendarName(row.calendarName());
    param.setTimezone(row.timezone());
    param.setHolidayRollRule(row.holidayRollRule());
    param.setCatchUpPolicy(row.catchUpPolicy());
    param.setCatchUpMaxDays(row.catchUpMaxDays());
    param.setEnabled(row.enabled());
    param.setDescription(row.description());
    param.setCreatedBy(operatorId);
    param.setUpdatedBy(operatorId);
    return param;
  }

  private static List<LocalDate> parseHolidays(String value, List<String> issues) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return List.of();
    }
    String body = normalized;
    if (body.startsWith("[") && body.endsWith("]")) {
      body = body.substring(1, body.length() - 1).replace("\"", "");
    }
    List<LocalDate> dates = new ArrayList<>();
    for (String part : body.split(",")) {
      String item = part.trim();
      if (!Texts.hasText(item)) {
        continue;
      }
      try {
        dates.add(LocalDate.parse(item));
      } catch (DateTimeParseException e) {
        issues.add("holidays must be comma separated yyyy-MM-dd dates");
      }
    }
    return List.copyOf(dates);
  }

  @Builder
  public record CalendarRow(
      int rowNo,
      String tenantId,
      String calendarCode,
      String calendarName,
      String timezone,
      String holidayRollRule,
      String catchUpPolicy,
      Integer catchUpMaxDays,
      List<LocalDate> holidays,
      Boolean enabled,
      String description) {}
}
