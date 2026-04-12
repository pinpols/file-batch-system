package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.CalendarDayType;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleBusinessCalendarExcelApplicationService;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.param.BusinessCalendarUpsertParam;
import com.example.batch.console.support.BusinessCalendarExcelImportStore;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
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
import java.io.ByteArrayOutputStream;
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
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** {@link ConsoleBusinessCalendarExcelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
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

  private static final Set<String> HOLIDAY_ROLL_RULES = HolidayRollRule.codes();
  private static final Set<String> CATCH_UP_POLICIES = CatchUpPolicyType.codes();
  private static final Set<String> DAY_TYPES = CalendarDayType.codes();
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> CALENDAR_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(COL_CALENDAR_CODE, requiredColumn("日历唯一编码，作为导入匹配键。", GUIDE_STR, "CAL_CN_STOCK")),
          Map.entry(COL_CALENDAR_NAME, requiredColumn("控制台展示的日历名称。", GUIDE_STR, "中国A股交易日历")),
          Map.entry("timezone", requiredColumn("日历时区。", "时区ID", "Asia/Shanghai")),
          Map.entry(
              COL_HOLIDAY_ROLL_RULE,
              requiredColumn("假日滚动规则。", "枚举", "SKIP", "SKIP", "NEXT_WORKDAY", "PREV_WORKDAY")),
          Map.entry(
              COL_CATCH_UP_POLICY,
              requiredColumn("补跑策略。", "枚举", "NONE", "NONE", "AUTO", "MANUAL_APPROVAL")),
          Map.entry(COL_CATCH_UP_MAX_DAYS, requiredColumn("补跑最大天数，必须大于等于 0。", "整数", "0")),
          Map.entry(COL_ENABLED, optionalColumn("日历是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, "FALSE")));

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> HOLIDAY_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_CALENDAR_CODE,
              requiredColumn(
                  "关联日历编码，必须与 business_calendar sheet 中的 calendar_code 对应。",
                  GUIDE_STR,
                  "CAL_CN_STOCK")),
          Map.entry(COL_BIZ_DATE, requiredColumn("日期，格式 yyyy-MM-dd。", "日期", "2026-01-01")),
          Map.entry(
              COL_DAY_TYPE, requiredColumn("日期类型。", "枚举", "HOLIDAY", "HOLIDAY", "WORKDAY_OVERRIDE")),
          Map.entry(COL_HOLIDAY_NAME, optionalColumn("假日名称。", GUIDE_STR, "元旦")),
          Map.entry(COL_DESCRIPTION, optionalColumn("备注说明。", GUIDE_STR, "元旦法定假日")));

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final BusinessCalendarExcelImportStore importStore;

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
    byte[] workbookBytes = writeWorkbook(calendars, allHolidays);
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
    byte[] workbookBytes = writeWorkbook(List.of(), List.of());
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
    byte[] workbookBytes = writePreviewWorkbook(session, result);
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
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();

    int[] calendarCounts = applyCalendarRows(result.calendarRows(), session.tenantId(), request.getReason(), operatorId, traceId);
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

  // ── session ──

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

  // ── parse ──

  private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
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
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + exception.getMessage());
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
            StringUtils.hasText(rowValues.get(COL_TENANT_ID))
                ? rowValues.get(COL_TENANT_ID)
                : tenantId);
      }
      rows.add(rowValues);
    }
    return rows;
  }

  // ── validation ──

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
    if (!StringUtils.hasText(effectiveTenant)) {
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

  // ── field validators ──

  private String requireText(
      Map<String, String> values, String key, int maxLength, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
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
    if (!StringUtils.hasText(normalized)) {
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
    if (!StringUtils.hasText(normalized)) {
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
    if (!StringUtils.hasText(normalized)) {
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
    if (!StringUtils.hasText(normalized)) {
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

  // ── Excel read helpers ──

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
      if (StringUtils.hasText(header)) {
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
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "excel header missing in sheet " + sheetName + ": " + String.join(", ", missing));
    }
  }

  private boolean rowIsBlank(Row row, DataFormatter formatter) {
    for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
      String value = normalize(formatter.formatCellValue(row.getCell(cellIndex)));
      if (StringUtils.hasText(value)) {
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

  // ── Excel write ──

  private byte[] writeWorkbook(
      List<Map<String, Object>> calendars, List<Map<String, Object>> holidays) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      // Sheet 1: business_calendar
      Sheet calendarSheet = workbook.createSheet(CALENDAR_SHEET_NAME);
      calendarSheet.createFreezePane(0, 1);
      writeTemplateHeaders(calendarSheet, CALENDAR_COLUMNS, CALENDAR_COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (Map<String, Object> row : calendars) {
        Row dataRow = calendarSheet.createRow(rowIndex++);
        for (int i = 0; i < CALENDAR_COLUMNS.size(); i++) {
          String header = CALENDAR_COLUMNS.get(i);
          Cell cell = dataRow.createCell(i);
          Object value = mapCalendarExportValue(header, row);
          cell.setCellValue(value == null ? "" : String.valueOf(value));
        }
      }
      applyCalendarValidations(calendarSheet);
      setWidths(calendarSheet, CALENDAR_COLUMNS);

      // Sheet 2: calendar_holiday
      Sheet holidaySheet = workbook.createSheet(HOLIDAY_SHEET_NAME);
      holidaySheet.createFreezePane(0, 1);
      writeTemplateHeaders(holidaySheet, HOLIDAY_COLUMNS, HOLIDAY_COLUMN_GUIDES, workbook);
      int holidayRowIndex = 1;
      for (Map<String, Object> row : holidays) {
        Row dataRow = holidaySheet.createRow(holidayRowIndex++);
        for (int i = 0; i < HOLIDAY_COLUMNS.size(); i++) {
          String header = HOLIDAY_COLUMNS.get(i);
          Cell cell = dataRow.createCell(i);
          Object value = mapHolidayExportValue(header, row);
          cell.setCellValue(value == null ? "" : String.valueOf(value));
        }
      }
      applyHolidayValidations(holidaySheet);
      setWidths(holidaySheet, HOLIDAY_COLUMNS);

      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      workbook.dispose();
      return out.toByteArray();
    } catch (IOException exception) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
    }
  }

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

  private byte[] writePreviewWorkbook(ParsedSession session, ValidationResult result) {
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      // Sheet 1: business_calendar
      Sheet calendarSheet = workbook.createSheet(CALENDAR_SHEET_NAME);
      calendarSheet.createFreezePane(0, 1);
      writeTemplateHeaders(calendarSheet, CALENDAR_COLUMNS, CALENDAR_COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (Map<String, String> rawRow : session.calendarRows()) {
        Row dataRow = calendarSheet.createRow(rowIndex++);
        for (int i = 0; i < CALENDAR_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(CALENDAR_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyCalendarValidations(calendarSheet);
      setWidths(calendarSheet, CALENDAR_COLUMNS);

      // Sheet 2: calendar_holiday
      Sheet holidaySheet = workbook.createSheet(HOLIDAY_SHEET_NAME);
      holidaySheet.createFreezePane(0, 1);
      writeTemplateHeaders(holidaySheet, HOLIDAY_COLUMNS, HOLIDAY_COLUMN_GUIDES, workbook);
      int holidayRowIndex = 1;
      for (Map<String, String> rawRow : session.holidayRows()) {
        Row dataRow = holidaySheet.createRow(holidayRowIndex++);
        for (int i = 0; i < HOLIDAY_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(HOLIDAY_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyHolidayValidations(holidaySheet);
      setWidths(holidaySheet, HOLIDAY_COLUMNS);

      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);

      List<WorkbookIssue> workbookIssues = new ArrayList<>();
      for (ConsoleBusinessCalendarExcelRowIssueResponse issue : result.issues()) {
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
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate preview excel workbook");
    }
  }

  private void applyCalendarValidations(Sheet sheet) {
    addDropdownValidation(
        sheet,
        4,
        HOLIDAY_ROLL_RULES.toArray(String[]::new),
        "holiday_roll_rule 填写提示",
        "请从下拉列表中选择假日滚动规则。");
    addDropdownValidation(
        sheet,
        5,
        CATCH_UP_POLICIES.toArray(String[]::new),
        "catch_up_policy 填写提示",
        "请从下拉列表中选择补跑策略。");
    addBooleanValidation(sheet, new int[] {7}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void applyHolidayValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 2, DAY_TYPES.toArray(String[]::new), "day_type 填写提示", "请从下拉列表中选择日期类型。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "business calendar maintenance template",
      "1. This workbook contains two sheets: business_calendar and calendar_holiday.",
      "2. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "3. calendar_code is the unique key for calendars. calendar_code + biz_date is the"
          + " unique key for holidays.",
      "4. holiday_roll_rule, catch_up_policy, day_type, and enabled have built-in dropdown"
          + " validation.",
      "5. calendar_code in calendar_holiday sheet must reference a calendar_code in"
          + " business_calendar sheet.",
      "6. Import flow is upload -> preview -> apply."
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  private void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("DICT");
    sheet.createFreezePane(0, 1);
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
    sheet.setColumnWidth(0, 24 * 256);
    sheet.setColumnWidth(1, 20 * 256);
    sheet.setColumnWidth(2, 36 * 256);
  }

  private void createValidationSheet(Workbook workbook) {
    ConsoleExcelStyles.createValidationSheet(workbook);
  }

  // ── change log ──

  private void logChange(
      String tenantId,
      String calendarCode,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "BUSINESS_CALENDAR",
            "configKey",
            calendarCode,
            "versionNo",
            1,
            "changeAction",
            action,
            "changeResult",
            "SUCCESS",
            "operatorType",
            "USER",
            "operatorId",
            ConsoleTextSanitizer.safeInput(operatorId, 64),
            "traceId",
            ConsoleTextSanitizer.safeInput(traceId, 128),
            "changeSummaryJson",
            JsonUtils.toJson(
                mapOf(
                    "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                    "detail", mapOf("calendarCode", calendarCode)))));
  }

  // ── helpers ──

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private String fileNameOrDefault(String fileName) {
    if (!StringUtils.hasText(fileName)) {
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

  // ── inner records ──

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
