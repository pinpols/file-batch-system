package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.normalize;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalBoolean;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireEnum;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireInteger;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireText;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.resolveTenantField;

import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.param.BusinessCalendarUpsertParam;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;

/** Shared parser/upsert helper for business_calendar Excel rows. */
public final class BusinessCalendarExcelRowParser {

  public static final String SHEET_NAME = BusinessCalendarExcelSchema.SHEET_NAME;

  private static final Set<String> HOLIDAY_ROLL_RULES = DictEnum.codes(HolidayRollRule.class);
  private static final Set<String> CATCH_UP_POLICIES = DictEnum.codes(CatchUpPolicyType.class);

  private BusinessCalendarExcelRowParser() {}

  public static CalendarRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    return CalendarRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .calendarCode(
            requireText(values, BusinessCalendarExcelSchema.COL_CALENDAR_CODE, 128, issues))
        .calendarName(
            requireText(values, BusinessCalendarExcelSchema.COL_CALENDAR_NAME, 256, issues))
        .timezone(requireText(values, BusinessCalendarExcelSchema.COL_TIMEZONE, 64, issues))
        .holidayRollRule(
            requireEnum(
                values,
                BusinessCalendarExcelSchema.COL_HOLIDAY_ROLL_RULE,
                HOLIDAY_ROLL_RULES,
                32,
                issues))
        .catchUpPolicy(
            requireEnum(
                values,
                BusinessCalendarExcelSchema.COL_CATCH_UP_POLICY,
                CATCH_UP_POLICIES,
                32,
                issues))
        .catchUpMaxDays(
            requireInteger(values, BusinessCalendarExcelSchema.COL_CATCH_UP_MAX_DAYS, 0, issues))
        .holidays(parseHolidays(values.get(BusinessCalendarExcelSchema.COL_HOLIDAYS), issues))
        .enabled(optionalBoolean(values, ConfigPackageExcelValidator.COL_ENABLED, true, issues))
        .description(normalize(values.get(ConfigPackageExcelValidator.COL_DESCRIPTION)))
        .build();
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
