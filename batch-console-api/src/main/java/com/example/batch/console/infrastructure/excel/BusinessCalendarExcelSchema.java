package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DESCRIPTION;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_ENABLED;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TENANT_ID;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared business_calendar Excel columns and row projections. */
public final class BusinessCalendarExcelSchema {

  public static final String SHEET_NAME = "business_calendar";

  public static final String COL_CALENDAR_CODE = "calendar_code";
  public static final String COL_CALENDAR_NAME = "calendar_name";
  public static final String COL_TIMEZONE = "timezone";
  public static final String COL_HOLIDAY_ROLL_RULE = "holiday_roll_rule";
  public static final String COL_CATCH_UP_POLICY = "catch_up_policy";
  public static final String COL_CATCH_UP_MAX_DAYS = "catch_up_max_days";
  public static final String COL_HOLIDAYS = "holidays";

  public static final String ROW_TENANT_ID = "tenantId";
  public static final String ROW_CALENDAR_CODE = "calendarCode";
  public static final String ROW_CALENDAR_NAME = "calendarName";
  public static final String ROW_TIMEZONE = "timezone";
  public static final String ROW_HOLIDAY_ROLL_RULE = "holidayRollRule";
  public static final String ROW_CATCH_UP_POLICY = "catchUpPolicy";
  public static final String ROW_CATCH_UP_MAX_DAYS = "catchUpMaxDays";

  public static final List<String> COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_CALENDAR_CODE,
          COL_CALENDAR_NAME,
          COL_TIMEZONE,
          COL_HOLIDAY_ROLL_RULE,
          COL_CATCH_UP_POLICY,
          COL_CATCH_UP_MAX_DAYS,
          COL_HOLIDAYS,
          COL_ENABLED,
          COL_DESCRIPTION);

  private BusinessCalendarExcelSchema() {}

  public static Map<String, Object> toExportRow(Map<String, Object> row, String holidaysText) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put(COL_TENANT_ID, row.get(ROW_TENANT_ID));
    item.put(COL_CALENDAR_CODE, row.get(ROW_CALENDAR_CODE));
    item.put(COL_CALENDAR_NAME, row.get(ROW_CALENDAR_NAME));
    item.put(COL_TIMEZONE, row.get(ROW_TIMEZONE));
    item.put(COL_HOLIDAY_ROLL_RULE, row.get(ROW_HOLIDAY_ROLL_RULE));
    item.put(COL_CATCH_UP_POLICY, row.get(ROW_CATCH_UP_POLICY));
    item.put(COL_CATCH_UP_MAX_DAYS, row.get(ROW_CATCH_UP_MAX_DAYS));
    item.put(COL_HOLIDAYS, holidaysText);
    item.put(COL_ENABLED, row.get(COL_ENABLED));
    item.put(COL_DESCRIPTION, row.get(COL_DESCRIPTION));
    return item;
  }
}
