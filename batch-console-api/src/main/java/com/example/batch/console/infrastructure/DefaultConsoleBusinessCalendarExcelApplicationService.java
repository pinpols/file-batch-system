package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.CalendarDayType;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.ConsoleBusinessCalendarExcelApplicationService;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.param.BusinessCalendarUpsertParam;
import com.example.batch.console.support.BusinessCalendarExcelImportStore;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.BusinessCalendarExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleBusinessCalendarExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleBusinessCalendarResponse;
import com.example.batch.console.web.response.ConsoleCalendarHolidayResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DefaultConsoleBusinessCalendarExcelApplicationService
    implements ConsoleBusinessCalendarExcelApplicationService {

  private static final String CALENDAR_SHEET_NAME = "business_calendar";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_CALENDAR_NAME = "calendar_name";
  private static final String COL_CATCH_UP_MAX_DAYS = "catch_up_max_days";
  private static final String COL_BIZ_DATE = "biz_date";
  private static final String COL_HOLIDAY_NAME = "holiday_name";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_CALENDAR_CODE = "calendar_code";
  private static final String COL_TENANT_ID = "tenant_id";
  private static final String COL_HOLIDAY_ROLL_RULE = "holiday_roll_rule";
  private static final String COL_CATCH_UP_POLICY = "catch_up_policy";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_DAY_TYPE = "day_type";
  private static final String GUIDE_STR = "字符串";
  private static final String COL_DESCRIPTION = "description";
  private static final String HOLIDAY_SHEET_NAME = "calendar_holiday";

  private static final List<String> CALENDAR_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_CALENDAR_CODE,
          COL_CALENDAR_NAME,
          "timezone",
          COL_HOLIDAY_ROLL_RULE,
          COL_CATCH_UP_POLICY,
          COL_CATCH_UP_MAX_DAYS,
          COL_ENABLED);
  private static final Set<String> CALENDAR_REQUIRED_HEADERS = Set.copyOf(CALENDAR_COLUMNS);

  private static final List<String> HOLIDAY_COLUMNS =
      List.of(COL_CALENDAR_CODE, COL_BIZ_DATE, COL_DAY_TYPE, COL_HOLIDAY_NAME, COL_DESCRIPTION);
  private static final Set<String> HOLIDAY_REQUIRED_HEADERS = Set.copyOf(HOLIDAY_COLUMNS);

  private static final Set<String> HOLIDAY_ROLL_RULES = DictEnum.codes(HolidayRollRule.class);
  private static final Set<String> CATCH_UP_POLICIES = DictEnum.codes(CatchUpPolicyType.class);
  private static final Set<String> DAY_TYPES = DictEnum.codes(CalendarDayType.class);
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final BusinessCalendarExcelImportStore importStore;
  private final BusinessCalendarExcelWorkbookWriter workbookWriter;

  @Override
  public ResponseEntity<InputStreamResource> exportBusinessCalendars(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> calendars =
        businessCalendarMapper.selectByQuery(resolvedTenantId, null, null, null);
    List<Map<String, Object>> allHolidays = new ArrayList<>();
    for (Map<String, Object> calendar : calendars) {
      Long calendarId = ((Number) calendar.get("id")).longValue();
      String calendarCode = (String) calendar.get("calendarCode");
      List<Map<String, Object>> holidays = calendarHolidayMapper.selectByCalendarId(calendarId);
      for (Map<String, Object> holiday : holidays) {
        holiday.put(COL_CALENDAR_CODE, calendarCode);
      }
      allHolidays.addAll(holidays);
    }
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(calendars, allHolidays);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName =
        "business-calendar-" + resolvedTenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(body);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(List.of(), List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("business-calendar-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleBusinessCalendarExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ParsedWorkbook parsed = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
    String uploadToken =
        importStore.save(
            parsed.fileName(), parsed.tenantId(), parsed.calendarRows(), parsed.holidayRows());
    return new ConsoleBusinessCalendarExcelUploadResponse(
        uploadToken, parsed.fileName(),
        CALENDAR_SHEET_NAME, parsed.calendarRows().size(),
        HOLIDAY_SHEET_NAME, parsed.holidayRows().size());
  }

  @Override
  public ConsoleBusinessCalendarExcelPreviewResponse preview(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateAll(session);
    return new ConsoleBusinessCalendarExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        result.totalCalendarRows(),
        result.validCalendarRows(),
        result.invalidCalendarRows(),
        result.calendarRows().stream().map(this::toCalendarResponse).toList(),
        result.totalHolidayRows(),
        result.validHolidayRows(),
        result.invalidHolidayRows(),
        result.holidayRows().stream().map(this::toHolidayResponse).toList(),
        result.issues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateAll(session);
    byte[] workbookBytes =
        workbookWriter.writePreviewWorkbook(
            session.calendarRows(), session.holidayRows(), result.issues());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(
                    ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()))
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  @Transactional
  public ConsoleBusinessCalendarExcelApplyResponse apply(
      String uploadToken, BusinessCalendarExcelApplyRequest request) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateAll(session);
    if (result.invalidCalendarRows() > 0 || result.invalidHolidayRows() > 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();

    int[] calendarCounts =
        applyCalendarRows(
            result.calendarRows(), session.tenantId(), request.getReason(), operatorId, traceId);
    int appliedHolidayRows = applyHolidayRows(result.holidayRows(), session.tenantId());

    importStore.remove(uploadToken);
    return new ConsoleBusinessCalendarExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        result.calendarRows().size(),
        calendarCounts[0],
        calendarCounts[1],
        appliedHolidayRows);
  }

  /** Upserts all calendar rows and returns {@code [inserted, updated]} counts. */
  private int[] applyCalendarRows(
      List<CalendarRow> calendarRows,
      String tenantId,
      String reason,
      String operatorId,
      String traceId) {
    int inserted = 0;
    int updated = 0;
    for (CalendarRow row : calendarRows) {
      Map<String, Object> existing =
          businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, row.calendarCode());
      BusinessCalendarUpsertParam param = new BusinessCalendarUpsertParam();
      param.setTenantId(tenantId);
      param.setCalendarCode(row.calendarCode());
      param.setCalendarName(row.calendarName());
      param.setTimezone(row.timezone());
      param.setHolidayRollRule(row.holidayRollRule());
      param.setCatchUpPolicy(row.catchUpPolicy());
      param.setCatchUpMaxDays(row.catchUpMaxDays());
      param.setEnabled(row.enabled());
      param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
      param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
      businessCalendarMapper.upsertBusinessCalendar(param);
      if (existing == null || existing.isEmpty()) {
        inserted++;
        logChange(tenantId, row.calendarCode(), reason, operatorId, traceId, "CREATE");
      } else {
        updated++;
        logChange(tenantId, row.calendarCode(), reason, operatorId, traceId, "PUBLISH");
      }
    }
    return new int[] {inserted, updated};
  }

  /** Deletes and re-inserts holidays grouped by calendar_code; returns total rows inserted. */
  private int applyHolidayRows(List<HolidayRow> holidayRows, String tenantId) {
    // First pass: delete existing holidays for each referenced calendar
    for (HolidayRow row : holidayRows) {
      Map<String, Object> calendar =
          businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, row.calendarCode());
      if (calendar == null || calendar.isEmpty()) {
        continue;
      }
      Long calendarId = ((Number) calendar.get("id")).longValue();
      calendarHolidayMapper.deleteByCalendarId(calendarId);
    }
    // Second pass: batch-insert holidays per calendar
    Map<String, List<HolidayRow>> holidaysByCalendar = new LinkedHashMap<>();
    for (HolidayRow row : holidayRows) {
      holidaysByCalendar.computeIfAbsent(row.calendarCode(), k -> new ArrayList<>()).add(row);
    }
    int appliedHolidayRows = 0;
    for (Map.Entry<String, List<HolidayRow>> entry : holidaysByCalendar.entrySet()) {
      Map<String, Object> calendar =
          businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, entry.getKey());
      if (calendar == null || calendar.isEmpty()) {
        continue;
      }
      Long calendarId = ((Number) calendar.get("id")).longValue();
      calendarHolidayMapper.deleteByCalendarId(calendarId);
      List<Map<String, Object>> batchParams = new ArrayList<>();
      for (HolidayRow row : entry.getValue()) {
        Map<String, Object> holidayParam = new LinkedHashMap<>();
        holidayParam.put("calendarId", calendarId);
        holidayParam.put("bizDate", row.bizDate());
        holidayParam.put("dayType", row.dayType());
        holidayParam.put("holidayName", row.holidayName());
        holidayParam.put(COL_DESCRIPTION, row.description());
        batchParams.add(holidayParam);
      }
      if (!batchParams.isEmpty()) {
        calendarHolidayMapper.batchInsert(batchParams);
        appliedHolidayRows += batchParams.size();
      }
    }
    return appliedHolidayRows;
  }

  private ParsedSession loadSession(String uploadToken) {
    BusinessCalendarExcelImportStore.ExcelImportSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return new ParsedSession(
        session.fileName(),
        session.tenantId(),
        session.uploadedAt(),
        session.calendarRows(),
        session.holidayRows());
  }

  private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.no_sheet");
      }
      DataFormatter formatter = new DataFormatter();
      List<Map<String, String>> calendarRows =
          parseSheet(
              workbook,
              CALENDAR_SHEET_NAME,
              CALENDAR_COLUMNS,
              CALENDAR_REQUIRED_HEADERS,
              tenantId,
              formatter);
      List<Map<String, String>> holidayRows =
          parseSheet(
              workbook,
              HOLIDAY_SHEET_NAME,
              HOLIDAY_COLUMNS,
              HOLIDAY_REQUIRED_HEADERS,
              null,
              formatter);
      return new ParsedWorkbook(
          fileNameOrDefault(originalFileName), tenantId, calendarRows, holidayRows);
    } catch (BizException exception) {
      throw exception;
    } catch (Exception exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "failed to read excel workbook: " + exception.getMessage());
    }
  }

  private List<Map<String, String>> parseSheet(
      Workbook workbook,
      String sheetName,
      List<String> columns,
      Set<String> requiredHeaders,
      String tenantId,
      DataFormatter formatter) {
    Sheet sheet = workbook.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet not found: " + sheetName);
    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
    Guard.require(headerRow != null, "excel header row is missing in sheet: " + sheetName);
    Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
    validateHeaders(headerIndex, requiredHeaders, sheetName);
    List<Map<String, String>> rows = new ArrayList<>();
    for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || rowIsBlank(row, formatter)) {
        continue;
      }
      Map<String, String> rowValues = new LinkedHashMap<>();
      for (String header : columns) {
        Integer columnIndex = headerIndex.get(header);
        rowValues.put(header, normalize(cellText(row, columnIndex, formatter)));
      }
      if (tenantId != null && columns.contains(COL_TENANT_ID)) {
        rowValues.put(
            COL_TENANT_ID,
            Texts.hasText(rowValues.get(COL_TENANT_ID)) ? rowValues.get(COL_TENANT_ID) : tenantId);
      }
      rows.add(rowValues);
    }
    return rows;
  }

  private ValidationResult validateAll(ParsedSession session) {
    List<CalendarRow> calendarRows = new ArrayList<>();
    List<ConsoleBusinessCalendarExcelRowIssueResponse> issues = new ArrayList<>();
    Set<String> calendarCodes = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> rowValues : session.calendarRows()) {
      List<String> rowIssues = new ArrayList<>();
      CalendarRow row = toCalendarRow(session.tenantId(), rowNo, rowValues, rowIssues);
      String uniqueKey = row.calendarCode();
      if (uniqueKey != null && !calendarCodes.add(uniqueKey)) {
        rowIssues.add("duplicate calendar_code in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        calendarRows.add(row);
      } else {
        issues.add(
            new ConsoleBusinessCalendarExcelRowIssueResponse(
                CALENDAR_SHEET_NAME, rowNo, uniqueKey, List.copyOf(rowIssues)));
      }
      rowNo++;
    }

    List<HolidayRow> holidayRows = new ArrayList<>();
    Set<String> holidayUniqueKeys = new LinkedHashSet<>();
    int holidayRowNo = 2;
    for (Map<String, String> rowValues : session.holidayRows()) {
      List<String> rowIssues = new ArrayList<>();
      HolidayRow row = toHolidayRow(holidayRowNo, rowValues, rowIssues);
      // Validate calendar_code exists in calendar sheet
      if (row.calendarCode() != null && !calendarCodes.contains(row.calendarCode())) {
        rowIssues.add("calendar_code not found in business_calendar sheet: " + row.calendarCode());
      }
      String uniqueKey = row.calendarCode() + ":" + row.bizDate();
      if (!holidayUniqueKeys.add(uniqueKey)) {
        rowIssues.add("duplicate calendar_code + biz_date in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        holidayRows.add(row);
      } else {
        issues.add(
            new ConsoleBusinessCalendarExcelRowIssueResponse(
                HOLIDAY_SHEET_NAME, holidayRowNo, uniqueKey, List.copyOf(rowIssues)));
      }
      holidayRowNo++;
    }

    int totalCalendarRows = session.calendarRows().size();
    int totalHolidayRows = session.holidayRows().size();
    int invalidCalendarRows =
        (int) issues.stream().filter(i -> CALENDAR_SHEET_NAME.equals(i.sheetName())).count();
    int invalidHolidayRows =
        (int) issues.stream().filter(i -> HOLIDAY_SHEET_NAME.equals(i.sheetName())).count();
    return ValidationResult.builder()
        .totalCalendarRows(totalCalendarRows)
        .validCalendarRows(totalCalendarRows - invalidCalendarRows)
        .invalidCalendarRows(invalidCalendarRows)
        .calendarRows(calendarRows)
        .totalHolidayRows(totalHolidayRows)
        .validHolidayRows(totalHolidayRows - invalidHolidayRows)
        .invalidHolidayRows(invalidHolidayRows)
        .holidayRows(holidayRows)
        .issues(issues)
        .build();
  }

  private CalendarRow toCalendarRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = normalize(values.get(COL_TENANT_ID));
    if (!Texts.hasText(effectiveTenant)) {
      effectiveTenant = tenantId;
    } else if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return CalendarRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .calendarCode(requireText(values, COL_CALENDAR_CODE, 128, issues))
        .calendarName(requireText(values, COL_CALENDAR_NAME, 256, issues))
        .timezone(requireText(values, "timezone", 64, issues))
        .holidayRollRule(requireEnum(values, COL_HOLIDAY_ROLL_RULE, HOLIDAY_ROLL_RULES, 32, issues))
        .catchUpPolicy(requireEnum(values, COL_CATCH_UP_POLICY, CATCH_UP_POLICIES, 32, issues))
        .catchUpMaxDays(requireInteger(values, COL_CATCH_UP_MAX_DAYS, 0, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .build();
  }

  private HolidayRow toHolidayRow(int rowNo, Map<String, String> values, List<String> issues) {
    return HolidayRow.builder()
        .rowNo(rowNo)
        .calendarCode(requireText(values, COL_CALENDAR_CODE, 128, issues))
        .bizDate(requireDate(values, COL_BIZ_DATE, issues))
        .dayType(requireEnum(values, COL_DAY_TYPE, DAY_TYPES, 32, issues))
        .holidayName(optionalText(values, COL_HOLIDAY_NAME, 128, issues))
        .description(optionalText(values, COL_DESCRIPTION, 512, issues))
        .build();
  }

  private String requireText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  private String optionalText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    if (normalized.length() > maxLength) {
      issues.add(key + " too long (max " + maxLength + ")");
      return normalized.substring(0, maxLength);
    }
    return normalized;
  }

  private String requireEnum(
      Map<String, String> values,
      String key,
      Set<String> allowed,
      int maxLength,
      List<String> issues) {
    String normalized = requireText(values, key, maxLength, issues);
    if (normalized == null) {
      return null;
    }
    String normalizedUpper = normalized.toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalizedUpper)) {
      issues.add(key + " must be one of " + allowed);
    }
    return normalizedUpper;
  }

  private Integer requireInteger(
      Map<String, String> values, String key, int min, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return min;
    }
    try {
      int value = Integer.parseInt(normalized);
      if (value < min) {
        issues.add(key + " must be >= " + min);
      }
      return value;
    } catch (NumberFormatException exception) {
      issues.add(key + " must be integer");
      return min;
    }
  }

  private Boolean optionalBoolean(
      Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (List.of(GUIDE_TRUE, "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (List.of("FALSE", "N", "0", "NO").contains(upper)) {
      return false;
    }
    issues.add(key + " must be boolean");
    return defaultValue;
  }

  private LocalDate requireDate(Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    try {
      return LocalDate.parse(normalized, DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      issues.add(key + " must be date in yyyy-MM-dd format");
      return null;
    }
  }

  private String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  private Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
    Map<String, Integer> headers = new LinkedHashMap<>();
    for (int cellIndex = headerRow.getFirstCellNum();
        cellIndex < headerRow.getLastCellNum();
        cellIndex++) {
      Cell cell = headerRow.getCell(cellIndex);
      String header = normalize(formatter.formatCellValue(cell));
      if (Texts.hasText(header)) {
        headers.put(header, cellIndex);
      }
    }
    return headers;
  }

  private void validateHeaders(
      Map<String, Integer> headerIndex, Set<String> requiredHeaders, String sheetName) {
    Set<String> missing = new LinkedHashSet<>(requiredHeaders);
    missing.removeAll(headerIndex.keySet());
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "excel header missing in sheet " + sheetName + ": " + String.join(", ", missing));
    }
  }

  private boolean rowIsBlank(Row row, DataFormatter formatter) {
    for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
      String value = normalize(formatter.formatCellValue(row.getCell(cellIndex)));
      if (Texts.hasText(value)) {
        return false;
      }
    }
    return true;
  }

  private String cellText(Row row, Integer columnIndex, DataFormatter formatter) {
    if (columnIndex == null) {
      return null;
    }
    Cell cell = row.getCell(columnIndex);
    return cell == null ? null : formatter.formatCellValue(cell);
  }

  private void logChange(
      String tenantId,
      String calendarCode,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("BUSINESS_CALENDAR")
            .withKey(calendarCode)
            .action(action)
            .summary(
                JsonUtils.toJson(
                    mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail", mapOf("calendarCode", calendarCode))))
            .build());
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private String fileNameOrDefault(String fileName) {
    if (!Texts.hasText(fileName)) {
      return "business-calendar.xlsx";
    }
    return fileName;
  }

  private ConsoleBusinessCalendarResponse toCalendarResponse(CalendarRow row) {
    return new ConsoleBusinessCalendarResponse(
        null,
        row.tenantId(),
        row.calendarCode(),
        row.calendarName(),
        row.timezone(),
        row.holidayRollRule(),
        row.catchUpPolicy(),
        row.catchUpMaxDays(),
        row.enabled(),
        null,
        null);
  }

  private ConsoleCalendarHolidayResponse toHolidayResponse(HolidayRow row) {
    return new ConsoleCalendarHolidayResponse(
        null,
        row.calendarCode(),
        row.bizDate(),
        row.dayType(),
        row.holidayName(),
        row.description());
  }

  private record ParsedWorkbook(
      String fileName,
      String tenantId,
      List<Map<String, String>> calendarRows,
      List<Map<String, String>> holidayRows) {}

  private record ParsedSession(
      String fileName,
      String tenantId,
      Instant uploadedAt,
      List<Map<String, String>> calendarRows,
      List<Map<String, String>> holidayRows) {}

  @Builder
  private record ValidationResult(
      int totalCalendarRows,
      int validCalendarRows,
      int invalidCalendarRows,
      List<CalendarRow> calendarRows,
      int totalHolidayRows,
      int validHolidayRows,
      int invalidHolidayRows,
      List<HolidayRow> holidayRows,
      List<ConsoleBusinessCalendarExcelRowIssueResponse> issues) {}

  @Builder
  private record CalendarRow(
      int rowNo,
      String tenantId,
      String calendarCode,
      String calendarName,
      String timezone,
      String holidayRollRule,
      String catchUpPolicy,
      Integer catchUpMaxDays,
      Boolean enabled) {}

  @Builder
  private record HolidayRow(
      int rowNo,
      String calendarCode,
      LocalDate bizDate,
      String dayType,
      String holidayName,
      String description) {}
}
