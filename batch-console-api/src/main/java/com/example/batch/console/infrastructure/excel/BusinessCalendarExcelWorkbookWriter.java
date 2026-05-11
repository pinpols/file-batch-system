package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.CalendarDayType;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.web.response.file.ConsoleBusinessCalendarExcelRowIssueResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: 业务日历 Excel 模板/导出/预览的 workbook writer。
 *
 * <p>覆盖原 service ~270 行写盘逻辑(2 个 sheet + SheetSpec 模板 + 校验下拉 + README/字典/校验 sheet),自带列名/列说明/枚举集常量。
 */
@Component
@RequiredArgsConstructor
public class BusinessCalendarExcelWorkbookWriter {

  private final MessageSource messageSource;

  static final String CALENDAR_SHEET_NAME = "business_calendar";
  static final String HOLIDAY_SHEET_NAME = "calendar_holiday";

  private static final String COL_TENANT_ID = "tenant_id";
  private static final String COL_CALENDAR_CODE = "calendar_code";
  private static final String COL_CALENDAR_NAME = "calendar_name";
  private static final String COL_HOLIDAY_ROLL_RULE = "holiday_roll_rule";
  private static final String COL_CATCH_UP_POLICY = "catch_up_policy";
  private static final String COL_CATCH_UP_MAX_DAYS = "catch_up_max_days";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_BIZ_DATE = "biz_date";
  private static final String COL_DAY_TYPE = "day_type";
  private static final String COL_HOLIDAY_NAME = "holiday_name";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_STR = "字符串";
  private static final String GUIDE_TRUE = "TRUE";

  static final List<String> CALENDAR_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_CALENDAR_CODE,
          COL_CALENDAR_NAME,
          "timezone",
          COL_HOLIDAY_ROLL_RULE,
          COL_CATCH_UP_POLICY,
          COL_CATCH_UP_MAX_DAYS,
          COL_ENABLED);
  static final Set<String> CALENDAR_REQUIRED_HEADERS = Set.copyOf(CALENDAR_COLUMNS);

  static final List<String> HOLIDAY_COLUMNS =
      List.of(COL_CALENDAR_CODE, COL_BIZ_DATE, COL_DAY_TYPE, COL_HOLIDAY_NAME, COL_DESCRIPTION);
  static final Set<String> HOLIDAY_REQUIRED_HEADERS = Set.copyOf(HOLIDAY_COLUMNS);

  private static final Set<String> HOLIDAY_ROLL_RULES = DictEnum.codes(HolidayRollRule.class);
  private static final Set<String> CATCH_UP_POLICIES = DictEnum.codes(CatchUpPolicyType.class);
  private static final Set<String> DAY_TYPES = DictEnum.codes(CalendarDayType.class);

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> CALENDAR_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID,
              optionalColumn(
                  "excel.calendar.cal.tenant_id.desc", "excel.guide.format.string", "tenant-a")),
          Map.entry(
              COL_CALENDAR_CODE,
              requiredColumn(
                  "excel.calendar.cal.calendar_code.desc",
                  "excel.guide.format.string",
                  "CAL_CN_STOCK")),
          Map.entry(
              COL_CALENDAR_NAME,
              requiredColumn(
                  "excel.calendar.cal.calendar_name.desc",
                  "excel.guide.format.string",
                  "中国A股交易日历")),
          Map.entry(
              "timezone",
              requiredColumn(
                  "excel.calendar.cal.timezone.desc",
                  "excel.guide.format.timezone_id",
                  "Asia/Shanghai")),
          Map.entry(
              COL_HOLIDAY_ROLL_RULE,
              requiredColumn(
                  "excel.calendar.cal.holiday_roll_rule.desc",
                  "excel.guide.format.enum",
                  "SKIP",
                  "SKIP",
                  "NEXT_WORKDAY",
                  "PREV_WORKDAY")),
          Map.entry(
              COL_CATCH_UP_POLICY,
              requiredColumn(
                  "excel.calendar.cal.catch_up_policy.desc",
                  "excel.guide.format.enum",
                  "NONE",
                  "NONE",
                  "AUTO",
                  "MANUAL_APPROVAL")),
          Map.entry(
              COL_CATCH_UP_MAX_DAYS,
              requiredColumn(
                  "excel.calendar.cal.catch_up_max_days.desc", "excel.guide.format.integer", "0")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.calendar.cal.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  "FALSE")));

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> HOLIDAY_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_CALENDAR_CODE,
              requiredColumn(
                  "excel.calendar.hol.calendar_code.desc",
                  "excel.guide.format.string",
                  "CAL_CN_STOCK")),
          Map.entry(
              COL_BIZ_DATE,
              requiredColumn(
                  "excel.calendar.hol.biz_date.desc", "excel.guide.format.date", "2026-01-01")),
          Map.entry(
              COL_DAY_TYPE,
              requiredColumn(
                  "excel.calendar.hol.day_type.desc",
                  "excel.guide.format.enum",
                  "HOLIDAY",
                  "HOLIDAY",
                  "WORKDAY_OVERRIDE")),
          Map.entry(
              COL_HOLIDAY_NAME,
              optionalColumn(
                  "excel.calendar.hol.holiday_name.desc", "excel.guide.format.string", "元旦")),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn(
                  "excel.calendar.hol.description.desc", "excel.guide.format.string", "元旦法定假日")));

  public byte[] writeMaintenanceWorkbook(
      List<Map<String, Object>> calendars, List<Map<String, Object>> holidays) {
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeDataSheet(
          workbook,
          new SheetSpec(
              CALENDAR_SHEET_NAME,
              CALENDAR_COLUMNS,
              CALENDAR_COLUMN_GUIDES,
              this::mapCalendarExportValue,
              sheet -> applyCalendarValidations(sheet, locale)),
          calendars,
          locale);
      writeDataSheet(
          workbook,
          new SheetSpec(
              HOLIDAY_SHEET_NAME,
              HOLIDAY_COLUMNS,
              HOLIDAY_COLUMN_GUIDES,
              this::mapHolidayExportValue,
              sheet -> applyHolidayValidations(sheet, locale)),
          holidays,
          locale);
      createReadmeSheet(workbook, locale);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.generate_failed");
    }
  }

  public byte[] writePreviewWorkbook(
      List<Map<String, String>> calendarRawRows,
      List<Map<String, String>> holidayRawRows,
      List<ConsoleBusinessCalendarExcelRowIssueResponse> issues) {
    Locale locale = LocaleContextHolder.getLocale();
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet calendarSheet = workbook.createSheet(CALENDAR_SHEET_NAME);
      calendarSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(
          calendarSheet, CALENDAR_COLUMNS, CALENDAR_COLUMN_GUIDES, workbook, messageSource, locale);
      int rowIndex = 1;
      for (Map<String, String> rawRow : calendarRawRows) {
        Row dataRow = calendarSheet.createRow(rowIndex++);
        for (int i = 0; i < CALENDAR_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(CALENDAR_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyCalendarValidations(calendarSheet, locale);
      setWidths(calendarSheet, CALENDAR_COLUMNS);

      Sheet holidaySheet = workbook.createSheet(HOLIDAY_SHEET_NAME);
      holidaySheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(
          holidaySheet, HOLIDAY_COLUMNS, HOLIDAY_COLUMN_GUIDES, workbook, messageSource, locale);
      int holidayRowIndex = 1;
      for (Map<String, String> rawRow : holidayRawRows) {
        Row dataRow = holidaySheet.createRow(holidayRowIndex++);
        for (int i = 0; i < HOLIDAY_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(HOLIDAY_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyHolidayValidations(holidaySheet, locale);
      setWidths(holidaySheet, HOLIDAY_COLUMNS);

      createReadmeSheet(workbook, locale);
      createDictSheet(workbook);
      createValidationSheet(workbook);

      List<WorkbookIssue> workbookIssues = new ArrayList<>();
      for (ConsoleBusinessCalendarExcelRowIssueResponse issue : issues) {
        List<String> columns =
            CALENDAR_SHEET_NAME.equals(issue.sheetName()) ? CALENDAR_COLUMNS : HOLIDAY_COLUMNS;
        workbookIssues.addAll(
            ConsoleExcelPreviewWorkbookSupport.expandIssues(
                issue.sheetName(), issue.rowNo(), issue.messages(), columns));
      }
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(workbook, workbookIssues);

      List<WorkbookIssue> calendarIssues =
          workbookIssues.stream().filter(i -> CALENDAR_SHEET_NAME.equals(i.sheetName())).toList();
      ConsoleExcelPreviewWorkbookSupport.addIssueComments(
          calendarSheet, CALENDAR_COLUMNS, calendarIssues, 1);

      List<WorkbookIssue> holidayIssues =
          workbookIssues.stream().filter(i -> HOLIDAY_SHEET_NAME.equals(i.sheetName())).toList();
      ConsoleExcelPreviewWorkbookSupport.addIssueComments(
          holidaySheet, HOLIDAY_COLUMNS, holidayIssues, 1);

      return ConsoleExcelPreviewWorkbookSupport.toBytes(workbook);
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.preview_workbook_failed");
    }
  }

  private void writeDataSheet(
      Workbook workbook, SheetSpec spec, List<Map<String, Object>> rows, Locale locale) {
    Sheet sheet = workbook.createSheet(spec.name());
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, spec.columns(), spec.guides(), workbook, messageSource, locale);
    int rowIndex = 1;
    for (Map<String, Object> row : rows) {
      Row dataRow = sheet.createRow(rowIndex++);
      for (int i = 0; i < spec.columns().size(); i++) {
        String header = spec.columns().get(i);
        Cell cell = dataRow.createCell(i);
        Object value = spec.valueMapper().apply(header, row);
        cell.setCellValue(
            value == null ? "" : ConsoleExcelStyles.escapeFormula(String.valueOf(value)));
      }
    }
    spec.validator().accept(sheet);
    setWidths(sheet, spec.columns());
  }

  private record SheetSpec(
      String name,
      List<String> columns,
      Map<String, ConsoleExcelStyles.ColumnGuide> guides,
      BiFunction<String, Map<String, Object>, Object> valueMapper,
      Consumer<Sheet> validator) {}

  private Object mapCalendarExportValue(String header, Map<String, Object> row) {
    return switch (header) {
      case COL_TENANT_ID -> row.get("tenantId");
      case COL_CALENDAR_CODE -> row.get("calendarCode");
      case COL_CALENDAR_NAME -> row.get("calendarName");
      case COL_HOLIDAY_ROLL_RULE -> row.get("holidayRollRule");
      case COL_CATCH_UP_POLICY -> row.get("catchUpPolicy");
      case COL_CATCH_UP_MAX_DAYS -> row.get("catchUpMaxDays");
      default -> row.get(header);
    };
  }

  private Object mapHolidayExportValue(String header, Map<String, Object> row) {
    return switch (header) {
      case COL_CALENDAR_CODE -> row.get(COL_CALENDAR_CODE);
      case COL_BIZ_DATE -> row.get("bizDate");
      case COL_DAY_TYPE -> row.get("dayType");
      case COL_HOLIDAY_NAME -> row.get("holidayName");
      default -> row.get(header);
    };
  }

  private void applyCalendarValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        4,
        HOLIDAY_ROLL_RULES.toArray(String[]::new),
        "excel.calendar.cal.holiday_roll_rule.prompt_title",
        "excel.calendar.cal.holiday_roll_rule.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        CATCH_UP_POLICIES.toArray(String[]::new),
        "excel.calendar.cal.catch_up_policy.prompt_title",
        "excel.calendar.cal.catch_up_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  private void applyHolidayValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        2,
        DAY_TYPES.toArray(String[]::new),
        "excel.calendar.hol.day_type.prompt_title",
        "excel.calendar.hol.day_type.prompt_box",
        messageSource,
        locale);
  }

  private void createReadmeSheet(Workbook workbook, Locale locale) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] keys = {
      "excel.calendar.readme.title",
      "excel.calendar.readme.line1",
      "excel.calendar.readme.line2",
      "excel.calendar.readme.line3",
      "excel.calendar.readme.line4",
      "excel.calendar.readme.line5",
      "excel.calendar.readme.line6"
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(messageSource.getMessage(keys[i], null, keys[i], locale));
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  private void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_DICT);
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_HOLIDAY_ROLL_RULE, "SKIP", "skip holiday, do not execute"},
      {COL_HOLIDAY_ROLL_RULE, "NEXT_WORKDAY", "move to next workday"},
      {COL_HOLIDAY_ROLL_RULE, "PREV_WORKDAY", "move to previous workday"},
      {COL_CATCH_UP_POLICY, "NONE", "no catch-up"},
      {COL_CATCH_UP_POLICY, "AUTO", "auto catch-up"},
      {COL_CATCH_UP_POLICY, "MANUAL_APPROVAL", "manual approval required"},
      {COL_DAY_TYPE, "HOLIDAY", "holiday"},
      {COL_DAY_TYPE, "WORKDAY_OVERRIDE", "workday override (e.g. weekend work)"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, "FALSE", "disabled"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    setGuideColumnWidths(sheet);
  }

  private void createValidationSheet(Workbook workbook) {
    ConsoleExcelStyles.createValidationSheet(workbook);
  }
}
