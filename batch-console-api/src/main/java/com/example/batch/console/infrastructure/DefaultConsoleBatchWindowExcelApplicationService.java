package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleBatchWindowExcelApplicationService;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.param.BatchWindowUpsertParam;
import com.example.batch.console.support.BatchWindowExcelImportStore;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.BatchWindowExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleBatchWindowExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleBatchWindowResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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

/** {@link ConsoleBatchWindowExcelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleBatchWindowExcelApplicationService
    implements ConsoleBatchWindowExcelApplicationService {

  private static final String SHEET_NAME = "batch_window";
  private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2})?$");
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "window_code",
          "window_name",
          "timezone",
          "start_time",
          "end_time",
          "end_strategy",
          "out_of_window_action",
          "allow_cross_day",
          "enabled",
          "description");
  private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
  private static final Set<String> END_STRATEGIES = BatchWindowEndStrategy.codes();
  private static final Set<String> OUT_OF_WINDOW_ACTIONS = OutOfWindowAction.codes();
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", "字符串", "tenant-a")),
          Map.entry("window_code", requiredColumn("窗口唯一编码，作为导入匹配键。", "字符串", "WIN_SETTLEMENT")),
          Map.entry("window_name", requiredColumn("控制台展示的窗口名称。", "字符串", "清算窗口")),
          Map.entry("timezone", requiredColumn("时区标识。", "字符串", "Asia/Shanghai")),
          Map.entry("start_time", requiredColumn("窗口开始时间，格式 HH:mm 或 HH:mm:ss。", "时间", "08:00")),
          Map.entry("end_time", requiredColumn("窗口结束时间，格式 HH:mm 或 HH:mm:ss。", "时间", "18:00")),
          Map.entry(
              "end_strategy",
              requiredColumn(
                  "窗口结束策略。", "枚举", "FINISH_RUNNING", "STOP", "FINISH_RUNNING", "CONTINUE")),
          Map.entry(
              "out_of_window_action", requiredColumn("窗口外操作策略。", "枚举", "WAIT", "WAIT", "FAIL")),
          Map.entry("allow_cross_day", optionalColumn("是否允许跨天。", "布尔值", "FALSE", "TRUE", "FALSE")),
          Map.entry("enabled", optionalColumn("窗口是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
          Map.entry("description", optionalColumn("窗口描述信息。", "字符串", "用于清算批处理的执行窗口")));

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final BatchWindowMapper batchWindowMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final BatchWindowExcelImportStore importStore;

  @Override
  public ResponseEntity<InputStreamResource> exportBatchWindows(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> rows =
        batchWindowMapper.selectByQuery(resolvedTenantId, null, null, null);
    byte[] workbookBytes = writeWorkbook(rows);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName =
        "batch-window-config-" + resolvedTenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
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
    byte[] workbookBytes = writeWorkbook(List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("batch-window-config-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleBatchWindowExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ConsoleSingleSheetExcelImportSupport.ParsedWorkbook parsedWorkbook =
        ConsoleSingleSheetExcelImportSupport.parseWorkbook(
            file.getBytes(),
            tenantId,
            file.getOriginalFilename(),
            "batch-window-config.xlsx",
            COLUMNS,
            REQUIRED_HEADERS);
    String uploadToken =
        importStore.save(
            parsedWorkbook.fileName(),
            parsedWorkbook.tenantId(),
            parsedWorkbook.sheetName(),
            parsedWorkbook.rows());
    return new ConsoleBatchWindowExcelUploadResponse(
        uploadToken,
        parsedWorkbook.fileName(),
        parsedWorkbook.sheetName(),
        parsedWorkbook.rows().size());
  }

  @Override
  public ConsoleBatchWindowExcelPreviewResponse preview(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    return new ConsoleBatchWindowExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        session.sheetName(),
        validationResult.totalRows(),
        validationResult.validRows(),
        validationResult.invalidRows(),
        validationResult.rows().stream().map(this::toResponse).toList(),
        validationResult.issues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    byte[] workbookBytes = writePreviewWorkbook(session, validationResult);
    return ConsoleSingleSheetExcelImportSupport.excelResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()),
        workbookBytes);
  }

  @Override
  @Transactional
  public ConsoleBatchWindowExcelApplyResponse apply(
      String uploadToken, BatchWindowExcelApplyRequest request) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    if (validationResult.invalidRows() > 0) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "excel contains invalid batch window rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();
    int inserted = 0;
    int updated = 0;
    for (WindowRow row : validationResult.rows()) {
      Map<String, Object> existing =
          batchWindowMapper.selectByUniqueKey(session.tenantId(), row.windowCode());
      BatchWindowUpsertParam param = new BatchWindowUpsertParam();
      param.setTenantId(session.tenantId());
      param.setWindowCode(row.windowCode());
      param.setWindowName(row.windowName());
      param.setTimezone(row.timezone());
      param.setStartTime(row.startTime());
      param.setEndTime(row.endTime());
      param.setEndStrategy(row.endStrategy());
      param.setOutOfWindowAction(row.outOfWindowAction());
      param.setAllowCrossDay(row.allowCrossDay());
      param.setEnabled(row.enabled());
      param.setDescription(row.description());
      batchWindowMapper.upsertBatchWindow(param);
      if (existing == null || existing.isEmpty()) {
        inserted++;
        logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "CREATE");
      } else {
        updated++;
        logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "PUBLISH");
      }
    }
    importStore.remove(uploadToken);
    return new ConsoleBatchWindowExcelApplyResponse(
        uploadToken, session.tenantId(), validationResult.rows().size(), inserted, updated);
  }

  private ConsoleSingleSheetExcelImportSupport.ParsedSession loadSession(String uploadToken) {
    return ConsoleSingleSheetExcelImportSupport.loadSession(
        uploadToken, importStore.get(uploadToken), tenantGuard);
  }

  private ValidationResult validateRows(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session) {
    List<WindowRow> rows = new ArrayList<>();
    List<ConsoleBatchWindowExcelRowIssueResponse> issues = new ArrayList<>();
    Set<String> uniqueKeys = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> rowValues : session.rows()) {
      List<String> rowIssues = new ArrayList<>();
      WindowRow row = toWindowRow(session.tenantId(), rowNo, rowValues, rowIssues);
      String uniqueKey = row.windowCode();
      if (!uniqueKeys.add(uniqueKey)) {
        rowIssues.add("duplicate window code in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        rows.add(row);
      } else {
        issues.add(
            new ConsoleBatchWindowExcelRowIssueResponse(
                rowNo, uniqueKey, row.windowCode(), List.copyOf(rowIssues)));
      }
      rowNo++;
    }
    int totalRows = session.rows().size();
    return ValidationResult.builder()
        .counts(
            ValidationCounts.builder()
                .totalRows(totalRows)
                .validRows(rows.size())
                .invalidRows(totalRows - rows.size())
                .build())
        .data(ValidationData.builder().rows(rows).issues(issues).build())
        .build();
  }

  private WindowRow toWindowRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!StringUtils.hasText(effectiveTenant)) {
      effectiveTenant = tenantId;
    } else if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return WindowRow.builder()
        .identity(
            WindowIdentity.builder()
                .rowNo(rowNo)
                .tenantId(effectiveTenant)
                .windowCode(requireText(values, "window_code", 128, issues))
                .build())
        .definition(
            WindowDefinition.builder()
                .windowName(requireText(values, "window_name", 256, issues))
                .timezone(requireText(values, "timezone", 64, issues))
                .startTime(requireTime(values, "start_time", issues))
                .endTime(requireTime(values, "end_time", issues))
                .endStrategy(requireEnum(values, "end_strategy", END_STRATEGIES, 32, issues))
                .outOfWindowAction(
                    requireEnum(values, "out_of_window_action", OUT_OF_WINDOW_ACTIONS, 32, issues))
                .build())
        .settings(
            WindowSettings.builder()
                .allowCrossDay(optionalBoolean(values, "allow_cross_day", false, issues))
                .enabled(optionalBoolean(values, "enabled", true, issues))
                .description(optionalText(values, "description", 512, issues))
                .build())
        .build();
  }

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

  private String requireTime(Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    if (!TIME_PATTERN.matcher(normalized).matches()) {
      issues.add(key + " must be HH:mm or HH:mm:ss format");
    }
    return normalized;
  }

  private Boolean optionalBoolean(
      Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      return defaultValue;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (List.of("TRUE", "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (List.of("FALSE", "N", "0", "NO").contains(upper)) {
      return false;
    }
    issues.add(key + " must be boolean");
    return defaultValue;
  }

  private String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  private byte[] writeWorkbook(List<Map<String, Object>> rows) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(SHEET_NAME);
      dataSheet.createFreezePane(0, 1);
      writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (Map<String, Object> row : rows) {
        Row dataRow = dataSheet.createRow(rowIndex++);
        for (int i = 0; i < COLUMNS.size(); i++) {
          String header = COLUMNS.get(i);
          Cell cell = dataRow.createCell(i);
          Object value = row.get(header);
          cell.setCellValue(value == null ? "" : String.valueOf(value));
        }
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, COLUMNS);
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

  private byte[] writePreviewWorkbook(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session,
      ValidationResult validationResult) {
    List<WorkbookIssue> workbookIssues =
        validationResult.issues().stream()
            .flatMap(
                issue ->
                    ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        SHEET_NAME, issue.rowNo(), issue.messages(), COLUMNS)
                        .stream())
            .toList();
    return ConsoleSingleSheetExcelImportSupport.writePreviewWorkbook(
        session,
        COLUMNS,
        COLUMN_GUIDES,
        this::applyValidations,
        workbook -> {
          createReadmeSheet(workbook);
          createDictSheet(workbook);
          createValidationSheet(workbook);
        },
        workbookIssues,
        1,
        "failed to generate preview excel workbook");
  }

  private void applyValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 6, END_STRATEGIES.toArray(String[]::new), "end_strategy 填写提示", "请从下拉列表中选择窗口结束策略。");
    addDropdownValidation(
        sheet,
        7,
        OUT_OF_WINDOW_ACTIONS.toArray(String[]::new),
        "out_of_window_action 填写提示",
        "请从下拉列表中选择窗口外操作策略。");
    addBooleanValidation(sheet, new int[] {8, 9}, "布尔值填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "batch window config maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. window_code is the unique key used during preview and apply.",
      "3. end_strategy, out_of_window_action, allow_cross_day, and enabled have built-in"
          + " dropdown validation.",
      "4. start_time and end_time must be in HH:mm or HH:mm:ss format.",
      "5. Import flow is upload -> preview -> apply."
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
    writeHeaders(sheet, List.of("field", "value", "description"), dictHeaderStyle);
    String[][] rows = {
      {"end_strategy", "STOP", "stop immediately"},
      {"end_strategy", "FINISH_RUNNING", "finish running tasks"},
      {"end_strategy", "CONTINUE", "continue without restriction"},
      {"out_of_window_action", "WAIT", "wait until window opens"},
      {"out_of_window_action", "FAIL", "fail the task"},
      {"allow_cross_day", "TRUE", "allow cross-day window"},
      {"allow_cross_day", "FALSE", "disallow cross-day window"},
      {"enabled", "TRUE", "enabled"},
      {"enabled", "FALSE", "disabled"}
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

  private void logChange(
      String tenantId,
      WindowRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "BATCH_WINDOW",
            "configKey",
            row.windowCode(),
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
                    "detail",
                        mapOf(
                            "windowName", row.windowName(),
                            "timezone", row.timezone(),
                            "startTime", row.startTime(),
                            "endTime", row.endTime(),
                            "endStrategy", row.endStrategy(),
                            "outOfWindowAction", row.outOfWindowAction())))));
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private ConsoleBatchWindowResponse toResponse(WindowRow row) {
    return new ConsoleBatchWindowResponse(
        null,
        row.tenantId(),
        row.windowCode(),
        row.windowName(),
        row.timezone(),
        row.startTime(),
        row.endTime(),
        row.endStrategy(),
        row.outOfWindowAction(),
        row.allowCrossDay(),
        row.enabled(),
        row.description(),
        null,
        null);
  }

  @Builder
  private record ValidationResult(ValidationCounts counts, ValidationData data) {
    int totalRows() {
      return counts.totalRows();
    }

    int validRows() {
      return counts.validRows();
    }

    int invalidRows() {
      return counts.invalidRows();
    }

    List<WindowRow> rows() {
      return data.rows();
    }

    List<ConsoleBatchWindowExcelRowIssueResponse> issues() {
      return data.issues();
    }
  }

  @Builder
  private record ValidationCounts(int totalRows, int validRows, int invalidRows) {}

  @Builder
  private record ValidationData(
      List<WindowRow> rows, List<ConsoleBatchWindowExcelRowIssueResponse> issues) {}

  @Builder
  private record WindowRow(
      WindowIdentity identity, WindowDefinition definition, WindowSettings settings) {
    int rowNo() {
      return identity.rowNo();
    }

    String tenantId() {
      return identity.tenantId();
    }

    String windowCode() {
      return identity.windowCode();
    }

    String windowName() {
      return definition.windowName();
    }

    String timezone() {
      return definition.timezone();
    }

    String startTime() {
      return definition.startTime();
    }

    String endTime() {
      return definition.endTime();
    }

    String endStrategy() {
      return definition.endStrategy();
    }

    String outOfWindowAction() {
      return definition.outOfWindowAction();
    }

    Boolean allowCrossDay() {
      return settings.allowCrossDay();
    }

    Boolean enabled() {
      return settings.enabled();
    }

    String description() {
      return settings.description();
    }
  }

  @Builder
  private record WindowIdentity(int rowNo, String tenantId, String windowCode) {}

  @Builder
  private record WindowDefinition(
      String windowName,
      String timezone,
      String startTime,
      String endTime,
      String endStrategy,
      String outOfWindowAction) {}

  @Builder
  private record WindowSettings(Boolean allowCrossDay, Boolean enabled, String description) {}
}
