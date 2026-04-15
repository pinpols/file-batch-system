package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.AlertSeverity;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.param.AlertRoutingConfigUpsertParam;
import com.example.batch.console.support.AlertRoutingExcelImportStore;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.AlertRoutingExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingResponse;
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

/** {@link ConsoleAlertRoutingExcelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleAlertRoutingExcelApplicationService
    implements ConsoleAlertRoutingExcelApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_TEAM = "team";
  private static final String COL_RECEIVER = "receiver";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_SEVERITY = "severity";
  private static final String GUIDE_STR = "字符串";
  private static final String COL_ENABLED = "enabled";

  private static final String SHEET_NAME = "alert_routing_config";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "route_code",
          "route_name",
          COL_TEAM,
          "alert_group",
          COL_SEVERITY,
          COL_RECEIVER,
          "group_by",
          "group_wait_seconds",
          "group_interval_seconds",
          "repeat_interval_seconds",
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
  private static final Set<String> SEVERITIES = AlertSeverity.codes();
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry("route_code", requiredColumn("路由唯一编码，作为导入匹配键。", GUIDE_STR, "RT_BATCH_ERROR")),
          Map.entry("route_name", requiredColumn("控制台展示的路由名称。", GUIDE_STR, "批处理异常路由")),
          Map.entry(COL_TEAM, requiredColumn("负责该路由的团队或值班组。", GUIDE_STR, "ops")),
          Map.entry("alert_group", requiredColumn("通知引擎使用的告警分组。", GUIDE_STR, "batch")),
          Map.entry(
              COL_SEVERITY,
              requiredColumn("该路由处理的告警级别。", "枚举", "ERROR", "INFO", "WARN", "ERROR", "CRITICAL")),
          Map.entry(COL_RECEIVER, requiredColumn("目标接收方、通道或 webhook 别名。", GUIDE_STR, "slack-ops")),
          Map.entry("group_by", optionalColumn("用于去重和聚合的分组键，可选。", "表达式", "job_code")),
          Map.entry("group_wait_seconds", optionalColumn("首次聚合通知前的等待秒数，必须大于等于 0。", "整数", "30")),
          Map.entry(
              "group_interval_seconds", optionalColumn("两次聚合通知之间的最小间隔，必须大于等于 0。", "整数", "300")),
          Map.entry(
              "repeat_interval_seconds", optionalColumn("持续告警的重复通知间隔，必须大于等于 0。", "整数", "3600")),
          Map.entry(
              COL_ENABLED, optionalColumn("告警路由是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, "FALSE")),
          Map.entry(COL_DESCRIPTION, optionalColumn("面向运维人员的说明信息。", GUIDE_STR, "批处理失败默认路由")));

  private static final MediaType XLSX_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final AlertRoutingConfigMapper alertRoutingConfigMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final AlertRoutingExcelImportStore importStore;

  @Override
  public ResponseEntity<InputStreamResource> exportAlertRoutings(AlertRoutingQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    List<Map<String, Object>> rows =
        alertRoutingConfigMapper.selectByQuery(
            tenantId,
            request.getRouteCode(),
            request.getTeam(),
            request.getSeverity(),
            request.getEnabled(),
            null);
    byte[] workbookBytes = writeWorkbook(rows);
    String fileName =
        "alert-routing-config-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(XLSX_MEDIA_TYPE)
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] workbookBytes = writeWorkbook(List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("alert-routing-config-template.xlsx")
                .build()
                .toString())
        .contentType(XLSX_MEDIA_TYPE)
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleAlertRoutingExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ConsoleSingleSheetExcelImportSupport.ParsedWorkbook parsed =
        ConsoleSingleSheetExcelImportSupport.parseWorkbook(
            file.getBytes(),
            tenantId,
            file.getOriginalFilename(),
            "alert-routing-config.xlsx",
            COLUMNS,
            REQUIRED_HEADERS);
    String uploadToken =
        importStore.save(parsed.fileName(), parsed.tenantId(), parsed.sheetName(), parsed.rows());
    return new ConsoleAlertRoutingExcelUploadResponse(
        uploadToken, parsed.fileName(), parsed.sheetName(), parsed.rows().size());
  }

  @Override
  public ConsoleAlertRoutingExcelPreviewResponse preview(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateRows(session);
    return new ConsoleAlertRoutingExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        session.sheetName(),
        result.totalRows(),
        result.validRows(),
        result.invalidRows(),
        result.rows().stream().map(this::toResponse).toList(),
        result.issues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateRows(session);
    byte[] workbookBytes = writePreviewWorkbook(session, result);
    return ConsoleSingleSheetExcelImportSupport.excelResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()),
        workbookBytes);
  }

  @Override
  @Transactional
  public ConsoleAlertRoutingExcelApplyResponse apply(
      String uploadToken, AlertRoutingExcelApplyRequest request) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateRows(session);
    if (result.invalidRows() > 0) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid routing rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();
    int inserted = 0;
    int updated = 0;
    for (RoutingRow row : result.rows()) {
      Map<String, Object> existing =
          alertRoutingConfigMapper.selectByUniqueKey(session.tenantId(), row.routeCode());
      AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
      param.setTenantId(session.tenantId());
      param.setRouteCode(row.routeCode());
      param.setRouteName(row.routeName());
      param.setTeam(row.team());
      param.setAlertGroup(row.alertGroup());
      param.setSeverity(row.severity());
      param.setReceiver(row.receiver());
      param.setGroupBy(row.groupBy());
      param.setGroupWaitSeconds(row.groupWaitSeconds());
      param.setGroupIntervalSeconds(row.groupIntervalSeconds());
      param.setRepeatIntervalSeconds(row.repeatIntervalSeconds());
      param.setEnabled(row.enabled());
      param.setDescription(row.description());
      param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
      param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
      alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
      if (existing == null || existing.isEmpty()) {
        inserted++;
        logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "CREATE");
      } else {
        updated++;
        logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "PUBLISH");
      }
    }
    importStore.remove(uploadToken);
    return new ConsoleAlertRoutingExcelApplyResponse(
        uploadToken, session.tenantId(), result.rows().size(), inserted, updated);
  }

  // ── internal ──

  private ConsoleSingleSheetExcelImportSupport.ParsedSession loadSession(String uploadToken) {
    return ConsoleSingleSheetExcelImportSupport.loadSession(
        uploadToken, importStore.get(uploadToken), tenantGuard);
  }

  private ValidationResult validateRows(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session) {
    List<RoutingRow> rows = new ArrayList<>();
    List<ConsoleAlertRoutingExcelRowIssueResponse> issues = new ArrayList<>();
    Set<String> uniqueKeys = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> rowValues : session.rows()) {
      List<String> rowIssues = new ArrayList<>();
      RoutingRow row = toRoutingRow(session.tenantId(), rowNo, rowValues, rowIssues);
      String uniqueKey = row.routeCode();
      if (!uniqueKeys.add(uniqueKey)) {
        rowIssues.add("duplicate route code in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        rows.add(row);
      } else {
        issues.add(
            new ConsoleAlertRoutingExcelRowIssueResponse(
                rowNo, uniqueKey, row.routeCode(), List.copyOf(rowIssues)));
      }
      rowNo++;
    }
    int totalRows = session.rows().size();
    return new ValidationResult(totalRows, rows.size(), totalRows - rows.size(), rows, issues);
  }

  private RoutingRow toRoutingRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!StringUtils.hasText(effectiveTenant)) {
      effectiveTenant = tenantId;
    } else if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return RoutingRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .routeCode(requireText(values, "route_code", 128, issues))
        .routeName(requireText(values, "route_name", 256, issues))
        .team(requireText(values, COL_TEAM, 128, issues))
        .alertGroup(requireText(values, "alert_group", 128, issues))
        .severity(requireEnum(values, COL_SEVERITY, SEVERITIES, 16, issues))
        .receiver(requireText(values, COL_RECEIVER, 256, issues))
        .groupBy(optionalText(values, "group_by", 512, issues))
        .groupWaitSeconds(optionalInteger(values, "group_wait_seconds", 0, 30, issues))
        .groupIntervalSeconds(optionalInteger(values, "group_interval_seconds", 0, 300, issues))
        .repeatIntervalSeconds(optionalInteger(values, "repeat_interval_seconds", 0, 3600, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .description(optionalText(values, COL_DESCRIPTION, 1024, issues))
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
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      issues.add(key + " must be one of " + allowed);
    }
    return upper;
  }

  private Integer optionalInteger(
      Map<String, String> values, String key, int min, int defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!StringUtils.hasText(normalized)) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(normalized);
      if (value < min) {
        issues.add(key + " must be >= " + min);
      }
      return value;
    } catch (NumberFormatException e) {
      issues.add(key + " must be integer");
      return defaultValue;
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

  private String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  // ── workbook generation ──

  private byte[] writeWorkbook(List<Map<String, Object>> rows) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(SHEET_NAME);
      dataSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (Map<String, Object> row : rows) {
        Row dataRow = dataSheet.createRow(rowIndex++);
        for (int i = 0; i < COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          Object value = row.get(COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : String.valueOf(value));
        }
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, COLUMNS);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
    }
  }

  private byte[] writePreviewWorkbook(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session, ValidationResult result) {
    List<WorkbookIssue> workbookIssues =
        result.issues().stream()
            .flatMap(
                issue ->
                    ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        SHEET_NAME, issue.rowNo(), issue.messages(), COLUMNS)
                        .stream())
            .toList();
    return ConsoleSingleSheetExcelImportSupport.writePreviewWorkbook(
        new ConsoleSingleSheetExcelImportSupport.WritePreviewWorkbookParam(
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
            "failed to generate preview excel workbook"));
  }

  private void applyValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 5, SEVERITIES.toArray(String[]::new), "severity 填写提示", "请从下拉列表中选择告警级别。");
    addBooleanValidation(sheet, new int[] {11}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "alert routing config maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. severity and enabled have built-in dropdown validation.",
      "3. route_code is the unique key used during preview and apply.",
      "4. Timing fields accept integers in seconds and must be >= 0.",
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
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_SEVERITY, "INFO", "informational"},
      {COL_SEVERITY, "WARN", "warning"},
      {COL_SEVERITY, "ERROR", "error"},
      {COL_SEVERITY, "CRITICAL", "critical"},
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

  private void logChange(
      String tenantId,
      RoutingRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "ALERT_ROUTING",
            "configKey",
            row.routeCode(),
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
                            "routeName",
                            row.routeName(),
                            COL_TEAM,
                            row.team(),
                            COL_SEVERITY,
                            row.severity(),
                            COL_RECEIVER,
                            row.receiver())))));
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return map;
  }

  private ConsoleAlertRoutingResponse toResponse(RoutingRow row) {
    return new ConsoleAlertRoutingResponse(
        null,
        row.tenantId(),
        row.routeCode(),
        row.routeName(),
        row.team(),
        row.alertGroup(),
        row.severity(),
        row.receiver(),
        row.groupBy(),
        row.groupWaitSeconds(),
        row.groupIntervalSeconds(),
        row.repeatIntervalSeconds(),
        row.enabled(),
        row.description(),
        null,
        null);
  }

  // ── internal records ──

  @Builder
  private record RoutingRow(
      int rowNo,
      String tenantId,
      String routeCode,
      String routeName,
      String team,
      String alertGroup,
      String severity,
      String receiver,
      String groupBy,
      Integer groupWaitSeconds,
      Integer groupIntervalSeconds,
      Integer repeatIntervalSeconds,
      Boolean enabled,
      String description) {}

  private record ValidationResult(
      int totalRows,
      int validRows,
      int invalidRows,
      List<RoutingRow> rows,
      List<ConsoleAlertRoutingExcelRowIssueResponse> issues) {}
}
