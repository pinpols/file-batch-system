package com.example.batch.console.infrastructure.job;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.param.JobDefinitionMaintenanceUpdateParam;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.infrastructure.excel.JobDefinitionExcelWorkbookWriter;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.JobDefinitionExcelImportStore;
import com.example.batch.console.support.excel.JobDefinitionExcelImportStore.JobDefinitionExcelSession;
import com.example.batch.console.support.excel.JobDefinitionExcelImportStore.JobDefinitionRow;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.request.job.JobDefinitionExcelApplyRequest;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelRowIssueResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelRowResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelUploadResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/**
 * {@link com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobDefinitionExcelApplicationService
    implements ConsoleJobDefinitionExcelApplicationService {

  private static final String SHEET = "job_definition";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_JOB_TYPE = "job_type";
  private static final String COL_SCHEDULE_TYPE = "schedule_type";
  private static final String COL_EXECUTION_HANDLER = "execution_handler";
  private static final String COL_PARAM_SCHEMA = "param_schema";
  private static final String COL_DEFAULT_PARAMS = "default_params";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_NONE = "NONE";
  private static final String COL_SHARD_STRATEGY = "shard_strategy";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_RETRY_POLICY = "retry_policy";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_STR = "字符串";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "job_code",
          "job_name",
          COL_JOB_TYPE,
          "queue_code",
          "worker_group",
          COL_SCHEDULE_TYPE,
          "schedule_expr",
          "calendar_code",
          "window_code",
          COL_RETRY_POLICY,
          "retry_max_count",
          "timeout_seconds",
          COL_SHARD_STRATEGY,
          COL_EXECUTION_HANDLER,
          COL_PARAM_SCHEMA,
          COL_DEFAULT_PARAMS,
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final Set<String> HEADERS = Set.copyOf(COLUMNS);
  private static final Set<String> JOB_TYPES = DictEnum.codes(JobType.class);
  private static final Set<String> SCHEDULE_TYPES = Set.of("CRON", "FIXED_RATE", "MANUAL");
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  private static final Set<String> SHARD_STRATEGIES = DictEnum.codes(ShardStrategy.class);
  private static final Set<String> ENABLED_VALUES = Set.of(GUIDE_TRUE, GUIDE_FALSE);

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final JobDefinitionExcelImportStore importStore;
  private final ResourceQueueMapper resourceQueueMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final JobDefinitionExcelWorkbookWriter workbookWriter;

  @Override
  public ResponseEntity<InputStreamResource> exportJobDefinitions(
      JobDefinitionQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionQuery exportQuery =
        JobDefinitionQuery.builder()
            .tenantId(tenantId)
            .jobCode(request.getJobCode())
            .jobName(request.getJobName())
            .jobType(request.getJobType())
            .workerGroup(request.getWorkerGroup())
            .queueCode(request.getQueueCode())
            .scheduleType(request.getScheduleType())
            .enabled(request.getEnabled())
            .build();
    List<JobDefinitionEntity> rows = jobDefinitionMapper.selectByQuery(exportQuery);
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(rows);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName =
        "job-definition-maintenance-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
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
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("job-definition-maintenance-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleJobDefinitionExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ParsedWorkbook workbook = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
    String uploadToken =
        importStore.save(workbook.fileName(), workbook.tenantId(), workbook.rows());
    return new ConsoleJobDefinitionExcelUploadResponse(
        uploadToken, workbook.fileName(), workbook.rows().size());
  }

  @Override
  public ConsoleJobDefinitionExcelPreviewResponse preview(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validate(session);
    return new ConsoleJobDefinitionExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        validationResult.totalRows(),
        validationResult.validRows(),
        validationResult.invalidRows(),
        validationResult.rows().stream().map(this::toResponse).toList(),
        validationResult.issues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validate(session);
    byte[] workbookBytes =
        workbookWriter.writePreviewWorkbook(session.rows(), validationResult.issues());
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
  public ConsoleJobDefinitionExcelApplyResponse apply(
      String uploadToken, JobDefinitionExcelApplyRequest request) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validate(session);
    if (validationResult.invalidRows() > 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_job_definition_rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String updatedBy = metadata.operatorId();
    int updatedRows = 0;
    for (JobDefinitionRow row : validationResult.rows()) {
      JobDefinitionEntity existing =
          Guard.requireFound(
              jobDefinitionMapper.selectByUniqueKey(row.tenantId(), row.jobCode()),
              "job definition not found: " + row.jobCode());
      JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
      param.setTenantId(row.tenantId());
      param.setJobCode(row.jobCode());
      param.setJobName(effective(row.jobName(), existing.getJobName()));
      param.setQueueCode(effective(row.queueCode(), existing.getQueueCode()));
      param.setWorkerGroup(effective(row.workerGroup(), existing.getWorkerGroup()));
      param.setScheduleExpr(effective(row.scheduleExpr(), existing.getScheduleExpr()));
      param.setCalendarCode(effective(row.calendarCode(), existing.getCalendarCode()));
      param.setWindowCode(effective(row.windowCode(), existing.getWindowCode()));
      param.setRetryPolicy(effective(row.retryPolicy(), existing.getRetryPolicy()));
      param.setRetryMaxCount(effective(row.retryMaxCount(), existing.getRetryMaxCount()));
      param.setTimeoutSeconds(effective(row.timeoutSeconds(), existing.getTimeoutSeconds()));
      param.setShardStrategy(effective(row.shardStrategy(), existing.getShardStrategy()));
      param.setEnabled(effective(row.enabled(), existing.getEnabled()));
      param.setDescription(effective(row.description(), existing.getDescription()));
      param.setUpdatedBy(ConsoleTextSanitizer.safeInput(updatedBy, 64));
      jobDefinitionMapper.updateJobDefinitionMaintenance(param);
      logJobChange(row, request.getReason(), updatedBy, metadata.traceId(), existing);
      updatedRows++;
    }
    importStore.remove(uploadToken);
    return new ConsoleJobDefinitionExcelApplyResponse(
        uploadToken, session.tenantId(), validationResult.totalRows(), updatedRows);
  }

  private ParsedSession loadSession(String uploadToken) {
    JobDefinitionExcelSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return new ParsedSession(
        session.fileName(), session.tenantId(), session.uploadedAt(), session.rows());
  }

  private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.no_sheet");
      }
      List<JobDefinitionRow> rows = parseRows(findSheet(workbook, SHEET), tenantId);
      return new ParsedWorkbook(fileNameOrDefault(originalFileName), tenantId, rows);
    } catch (BizException exception) {
      throw exception;
    } catch (Exception exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "failed to read excel workbook: " + exception.getMessage());
    }
  }

  private Sheet findSheet(Workbook workbook, String sheetName) {
    Sheet sheet = workbook.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet missing: " + sheetName);
    return sheet;
  }

  private List<JobDefinitionRow> parseRows(Sheet sheet, String tenantId) {
    List<JobDefinitionRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet)) {
      Map<String, String> values = rowData.values();
      JobDefinitionRow row =
          JobDefinitionRow.builder()
              .rowNo(rowData.rowNo())
              .tenantId(tenantOrDefault(values.get("tenant_id"), tenantId))
              .jobCode(normalize(values.get("job_code")))
              .jobName(normalize(values.get("job_name")))
              .jobType(normalizeEnum(values.get(COL_JOB_TYPE)))
              .queueCode(CodeNormalizer.toConfigFormOrNull(values.get("queue_code")))
              .workerGroup(CodeNormalizer.toUpperOrNull(values.get("worker_group")))
              .scheduleType(normalizeEnum(values.get(COL_SCHEDULE_TYPE)))
              .scheduleExpr(normalize(values.get("schedule_expr")))
              .calendarCode(CodeNormalizer.toConfigFormOrNull(values.get("calendar_code")))
              .windowCode(CodeNormalizer.toConfigFormOrNull(values.get("window_code")))
              .retryPolicy(normalizeEnum(values.get(COL_RETRY_POLICY)))
              .retryMaxCount(parseInteger(values.get("retry_max_count")))
              .timeoutSeconds(parseInteger(values.get("timeout_seconds")))
              .shardStrategy(normalizeEnum(values.get(COL_SHARD_STRATEGY)))
              .executionHandler(normalize(values.get(COL_EXECUTION_HANDLER)))
              .paramSchema(normalize(values.get(COL_PARAM_SCHEMA)))
              .defaultParams(normalize(values.get(COL_DEFAULT_PARAMS)))
              .enabled(parseBoolean(values.get(COL_ENABLED), true))
              .description(normalize(values.get(COL_DESCRIPTION)))
              .build();
      rows.add(row);
    }
    return rows;
  }

  private List<SheetRow> readSheetRows(Sheet sheet) {
    DataFormatter formatter = new DataFormatter();
    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
    if (headerRow == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "excel header row is missing for sheet: " + sheet.getSheetName());
    }
    Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
    validateHeaders(sheet.getSheetName(), headerIndex, HEADERS);
    List<SheetRow> rows = new ArrayList<>();
    for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || rowIsBlank(row, formatter)) {
        continue;
      }
      Map<String, String> rowValues = new LinkedHashMap<>();
      for (String header : COLUMNS) {
        rowValues.put(header, normalize(cellText(row, headerIndex.get(header), formatter)));
      }
      rows.add(new SheetRow(row.getRowNum() + 1, rowValues));
    }
    return rows;
  }

  private ValidationResult validate(ParsedSession session) {
    List<ConsoleJobDefinitionExcelRowIssueResponse> issues = new ArrayList<>();
    List<JobDefinitionRow> validRows = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JobDefinitionRow row : session.rows()) {
      List<String> rowIssues = new ArrayList<>();
      String rowKey = row.jobCode();
      if (!hasText(row.tenantId()) || !hasText(row.jobCode())) {
        rowIssues.add("tenant_id and job_code are required");
      }
      if (!seen.add(row.tenantId() + "#" + row.jobCode())) {
        rowIssues.add("duplicate job definition in excel: " + row.tenantId() + "#" + row.jobCode());
      }
      JobDefinitionEntity existing =
          hasText(row.tenantId()) && hasText(row.jobCode())
              ? jobDefinitionMapper.selectByUniqueKey(row.tenantId(), row.jobCode())
              : null;
      if (existing == null) {
        rowIssues.add("job definition not found for maintenance: " + row.jobCode());
      } else {
        checkReadOnly(row.jobType(), existing.getJobType(), COL_JOB_TYPE, rowIssues);
        checkReadOnly(row.scheduleType(), existing.getScheduleType(), COL_SCHEDULE_TYPE, rowIssues);
        checkReadOnly(
            row.executionHandler(),
            existing.getExecutionHandler(),
            COL_EXECUTION_HANDLER,
            rowIssues);
        checkReadOnly(row.paramSchema(), existing.getParamSchema(), COL_PARAM_SCHEMA, rowIssues);
        checkReadOnly(
            row.defaultParams(), existing.getDefaultParams(), COL_DEFAULT_PARAMS, rowIssues);
      }
      if (hasText(row.retryPolicy()) && !RETRY_POLICIES.contains(row.retryPolicy())) {
        rowIssues.add("retry_policy must be one of " + RETRY_POLICIES);
      }
      if (hasText(row.shardStrategy()) && !SHARD_STRATEGIES.contains(row.shardStrategy())) {
        rowIssues.add("shard_strategy must be one of " + SHARD_STRATEGIES);
      }
      if (hasText(row.enabled() == null ? null : String.valueOf(row.enabled()))
          && !ENABLED_VALUES.contains(String.valueOf(row.enabled()).toUpperCase(Locale.ROOT))) {
        rowIssues.add("enabled must be TRUE or FALSE");
      }
      if (row.retryMaxCount() != null && row.retryMaxCount() < 0) {
        rowIssues.add("retry_max_count must be >= 0");
      }
      if (row.timeoutSeconds() != null && row.timeoutSeconds() < 0) {
        rowIssues.add("timeout_seconds must be >= 0");
      }
      if (hasText(row.queueCode())
          && resourceQueueMapper.selectByUniqueKey(row.tenantId(), row.queueCode()) == null) {
        rowIssues.add("queue_code references non-existent resource queue: " + row.queueCode());
      }
      if (hasText(row.calendarCode())
          && businessCalendarMapper.selectActiveByTenantAndCalendarCode(
                  row.tenantId(), row.calendarCode())
              == null) {
        rowIssues.add(
            "calendar_code references non-existent business calendar: " + row.calendarCode());
      }
      if (hasText(row.windowCode())
          && batchWindowMapper.selectByUniqueKey(row.tenantId(), row.windowCode()) == null) {
        rowIssues.add("window_code references non-existent batch window: " + row.windowCode());
      }
      if (row.paramSchema() != null) {
        try {
          JsonUtils.fromJson(row.paramSchema(), Object.class);
        } catch (IllegalArgumentException exception) {
          rowIssues.add("param_schema must be valid JSON");
        }
      }
      if (row.defaultParams() != null) {
        try {
          JsonUtils.fromJson(row.defaultParams(), Object.class);
        } catch (IllegalArgumentException exception) {
          rowIssues.add("default_params must be valid JSON");
        }
      }
      if (rowIssues.isEmpty()) {
        validRows.add(row);
      } else {
        issues.add(
            new ConsoleJobDefinitionExcelRowIssueResponse(
                SHEET, row.rowNo(), rowKey, row.jobCode(), List.copyOf(rowIssues)));
      }
    }
    return new ValidationResult(session.rows().size(), validRows, issues);
  }

  private void checkReadOnly(
      String incoming, String current, String field, List<String> rowIssues) {
    if (incoming != null
        && current != null
        && !Objects.equals(normalize(incoming), normalize(current))) {
      rowIssues.add(field + " is read-only and must match the existing job definition");
    }
    if (incoming != null && current == null && Texts.hasText(incoming)) {
      rowIssues.add(field + " is read-only and must match the existing job definition");
    }
  }

  private void logJobChange(
      JobDefinitionRow row,
      String reason,
      String updatedBy,
      String traceId,
      JobDefinitionEntity existing) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(row.tenantId(), updatedBy, traceId)
            .forType("JOB_DEFINITION")
            .withKey(row.jobCode())
            .action("BULK_UPDATE")
            .summary(
                JsonUtils.toJson(
                    mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail",
                            mapOf(
                                "jobName",
                                effective(row.jobName(), existing.getJobName()),
                                "queueCode",
                                effective(row.queueCode(), existing.getQueueCode()),
                                "workerGroup",
                                effective(row.workerGroup(), existing.getWorkerGroup()),
                                "scheduleExpr",
                                effective(row.scheduleExpr(), existing.getScheduleExpr()),
                                "retryPolicy",
                                effective(row.retryPolicy(), existing.getRetryPolicy()),
                                "retryMaxCount",
                                effective(row.retryMaxCount(), existing.getRetryMaxCount()),
                                "timeoutSeconds",
                                effective(row.timeoutSeconds(), existing.getTimeoutSeconds()),
                                "shardStrategy",
                                effective(row.shardStrategy(), existing.getShardStrategy()),
                                COL_ENABLED,
                                effective(row.enabled(), existing.getEnabled())))))
            .build());
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  private String normalizeEnum(String value) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return null;
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private Integer parseInteger(String value) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      return Integer.valueOf(normalized);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private Boolean parseBoolean(String value, Boolean defaultValue) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (List.of(GUIDE_TRUE, "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (List.of(GUIDE_FALSE, "N", "0", "NO").contains(upper)) {
      return false;
    }
    return defaultValue;
  }

  private boolean hasText(String value) {
    return Texts.hasText(normalize(value));
  }

  private String tenantOrDefault(String value, String tenantId) {
    String normalized = normalize(value);
    return Texts.hasText(normalized) ? normalized : tenantId;
  }

  private <T> T effective(T incoming, T current) {
    if (incoming == null) {
      return current;
    }
    if (incoming instanceof String str && !Texts.hasText(str)) {
      return current;
    }
    return incoming;
  }

  private String fileNameOrDefault(String fileName) {
    if (!Texts.hasText(fileName)) {
      return "job-definition-maintenance.xlsx";
    }
    return fileName;
  }

  private ConsoleJobDefinitionExcelRowResponse toResponse(JobDefinitionRow row) {
    return new ConsoleJobDefinitionExcelRowResponse(
        row.tenantId(),
        row.jobCode(),
        row.jobName(),
        row.jobType(),
        row.queueCode(),
        row.workerGroup(),
        row.scheduleType(),
        row.scheduleExpr(),
        row.calendarCode(),
        row.windowCode(),
        row.retryPolicy(),
        row.retryMaxCount(),
        row.timeoutSeconds(),
        row.shardStrategy(),
        row.executionHandler(),
        row.paramSchema(),
        row.defaultParams(),
        row.enabled(),
        row.description());
  }

  private void validateHeaders(
      String sheetName, Map<String, Integer> headerIndex, Set<String> requiredHeaders) {
    Set<String> missing = new LinkedHashSet<>(requiredHeaders);
    missing.removeAll(headerIndex.keySet());
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "excel header missing for sheet " + sheetName + ": " + String.join(", ", missing));
    }
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

  private record ParsedWorkbook(String fileName, String tenantId, List<JobDefinitionRow> rows) {}

  private record ParsedSession(
      String fileName, String tenantId, Instant uploadedAt, List<JobDefinitionRow> rows) {}

  private record ValidationResult(
      int totalRows,
      List<JobDefinitionRow> rows,
      List<ConsoleJobDefinitionExcelRowIssueResponse> issues) {
    int validRows() {
      return rows.size();
    }

    int invalidRows() {
      return issues.size();
    }
  }

  private record SheetRow(int rowNo, Map<String, String> values) {}
}
