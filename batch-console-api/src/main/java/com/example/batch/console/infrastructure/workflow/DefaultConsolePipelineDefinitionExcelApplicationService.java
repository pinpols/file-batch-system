package com.example.batch.console.infrastructure.workflow;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.workflow.ConsolePipelineDefinitionExcelApplicationService;
import com.example.batch.console.infrastructure.excel.PipelineExcelWorkbookWriter;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.PipelineDefinitionExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.file.PipelineDefinitionExcelApplyRequest;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelRowIssueResponse;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelUploadResponse;
import com.example.batch.console.web.response.workflow.PipelineDefinitionDetailResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

/** {@link ConsolePipelineDefinitionExcelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsolePipelineDefinitionExcelApplicationService
    implements ConsolePipelineDefinitionExcelApplicationService {

  private static final String PIPELINE_SHEET_NAME = "pipeline_definition";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_STEP_CODE = "step_code";
  private static final String COL_STEP_NAME = "step_name";
  private static final String COL_STEP_ORDER = "step_order";
  private static final String COL_IMPL_CODE = "impl_code";
  private static final String COL_STEP_PARAMS = "step_params";
  private static final String COL_TIMEOUT_SECONDS = "timeout_seconds";
  private static final String COL_RETRY_MAX_COUNT = "retry_max_count";
  private static final String STAGE_PARSE = "PARSE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String COL_STAGE_CODE = "stage_code";
  private static final String COL_ENABLED = "enabled";
  private static final String GUIDE_STR = "字符串";
  private static final String COL_JOB_CODE = "job_code";
  private static final String COL_VERSION = "version";
  private static final String COL_TENANT_ID = "tenant_id";
  private static final String COL_PIPELINE_TYPE = "pipeline_type";
  private static final String COL_RETRY_POLICY = "retry_policy";
  private static final String KEY_SEP_COLON = ":";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_PIPELINE_NAME = "pipeline_name";
  private static final String COL_BIZ_TYPE = "biz_type";
  private static final String COL_WORKER_GROUP = "worker_group";
  private static final String STAGE_DISPATCH = "DISPATCH";
  private static final String GUIDE_INT = "整数";
  private static final String STEP_SHEET_NAME = "pipeline_step_definition";

  private static final List<String> PIPELINE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_JOB_CODE,
          COL_PIPELINE_NAME,
          COL_PIPELINE_TYPE,
          COL_BIZ_TYPE,
          COL_WORKER_GROUP,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  private static final List<String> STEP_COLUMNS =
      List.of(
          COL_JOB_CODE,
          COL_VERSION,
          COL_STEP_CODE,
          COL_STEP_NAME,
          COL_STAGE_CODE,
          COL_STEP_ORDER,
          COL_IMPL_CODE,
          COL_STEP_PARAMS,
          COL_TIMEOUT_SECONDS,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_ENABLED);

  private static final Set<String> PIPELINE_REQUIRED_HEADERS = Set.copyOf(PIPELINE_COLUMNS);
  private static final Set<String> STEP_REQUIRED_HEADERS = Set.copyOf(STEP_COLUMNS);

  private static final Set<String> PIPELINE_TYPES = DictEnum.codes(PipelineType.class);
  private static final Set<String> STAGE_CODES =
      Set.of(
          "RECEIVE",
          "PREPROCESS",
          STAGE_PARSE,
          "VALIDATE",
          "LOAD",
          "GENERATE",
          "TRANSFER",
          STAGE_DISPATCH,
          "ACK");
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final PipelineDefinitionExcelImportStore importStore;
  private final PipelineExcelWorkbookWriter workbookWriter;

  @Override
  public ResponseEntity<InputStreamResource> exportPipelineDefinitions(
      String tenantId, String jobCode, String pipelineType, Boolean enabled) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> pipelines =
        pipelineDefinitionMapper.selectByQuery(
            resolvedTenantId, jobCode, pipelineType, enabled, null);
    List<Map<String, Object>> allSteps = new ArrayList<>();
    for (Map<String, Object> pipeline : pipelines) {
      Long pipelineId = ((Number) pipeline.get("id")).longValue();
      List<Map<String, Object>> steps =
          pipelineStepDefinitionMapper.selectByPipelineDefinitionId(pipelineId);
      String pipelineJobCode = String.valueOf(pipeline.get(COL_JOB_CODE));
      String pipelineVersion = String.valueOf(pipeline.get(COL_VERSION));
      for (Map<String, Object> step : steps) {
        Map<String, Object> enriched = new LinkedHashMap<>(step);
        enriched.put(COL_JOB_CODE, pipelineJobCode);
        enriched.put(COL_VERSION, pipelineVersion);
        allSteps.add(enriched);
      }
    }
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(pipelines, allSteps);
    String fileName =
        "pipeline-definition-" + resolvedTenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(List.of(), List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("pipeline-definition-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsolePipelineDefinitionExcelUploadResponse upload(MultipartFile file)
      throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ParsedWorkbook parsed = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
    String uploadToken =
        importStore.save(
            parsed.fileName(), parsed.tenantId(), parsed.pipelineRows(), parsed.stepRows());
    return new ConsolePipelineDefinitionExcelUploadResponse(
        uploadToken, parsed.fileName(), parsed.pipelineRows().size(), parsed.stepRows().size());
  }

  @Override
  public ConsolePipelineDefinitionExcelPreviewResponse preview(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateRows(session);
    List<PipelineDefinitionDetailResponse> responses = toPipelineResponses(result);
    return new ConsolePipelineDefinitionExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        result.totalPipelineRows(),
        result.validPipelineRows(),
        result.invalidPipelineRows(),
        result.totalStepRows(),
        result.validStepRows(),
        result.invalidStepRows(),
        responses,
        result.allIssues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateRows(session);
    byte[] workbookBytes =
        workbookWriter.writePreviewWorkbook(
            session.pipelineRows(), session.stepRows(), result.allIssues());
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
  public ConsolePipelineDefinitionExcelApplyResponse apply(
      String uploadToken, PipelineDefinitionExcelApplyRequest request) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult result = validateRows(session);
    if (result.invalidPipelineRows() > 0 || result.invalidStepRows() > 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();
    int inserted = 0;
    int updated = 0;
    int appliedSteps = 0;

    Map<String, List<StepRow>> stepsByKey =
        result.stepRows().stream()
            .collect(Collectors.groupingBy(s -> s.jobCode() + KEY_SEP_COLON + s.version()));

    for (PipelineRow pipelineRow : result.pipelineRows()) {
      String uniqueKey = pipelineRow.jobCode() + KEY_SEP_COLON + pipelineRow.version();
      Map<String, Object> existing =
          pipelineDefinitionMapper.selectByUniqueKey(
              session.tenantId(), pipelineRow.jobCode(), pipelineRow.version());
      Long pipelineId;
      if (existing == null || existing.isEmpty()) {
        Map<String, Object> insertParams =
            buildPipelineInsertParams(session.tenantId(), pipelineRow);
        pipelineDefinitionMapper.insert(insertParams);
        pipelineId = ((Number) insertParams.get("id")).longValue();
        inserted++;
        logChange(
            session.tenantId(), pipelineRow, request.getReason(), operatorId, traceId, "CREATE");
      } else {
        pipelineId = ((Number) existing.get("id")).longValue();
        Map<String, Object> updateParams =
            buildPipelineUpdateParams(session.tenantId(), pipelineId, pipelineRow);
        pipelineDefinitionMapper.update(updateParams);
        updated++;
        logChange(
            session.tenantId(), pipelineRow, request.getReason(), operatorId, traceId, "PUBLISH");
      }

      pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(pipelineId);
      List<StepRow> steps = stepsByKey.getOrDefault(uniqueKey, List.of());
      for (StepRow stepRow : steps) {
        Map<String, Object> stepParams = buildStepInsertParams(pipelineId, stepRow);
        pipelineStepDefinitionMapper.insert(stepParams);
        appliedSteps++;
      }
    }

    importStore.remove(uploadToken);
    return new ConsolePipelineDefinitionExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        result.pipelineRows().size(),
        inserted,
        updated,
        appliedSteps);
  }

  private ParsedSession loadSession(String uploadToken) {
    PipelineDefinitionExcelImportStore.ExcelImportSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return new ParsedSession(
        session.fileName(),
        session.tenantId(),
        session.uploadedAt(),
        session.pipelineRows(),
        session.stepRows());
  }

  private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.no_sheet");
      }
      List<Map<String, String>> pipelineRows =
          parseSheet(
              workbook, PIPELINE_SHEET_NAME, PIPELINE_COLUMNS, PIPELINE_REQUIRED_HEADERS, tenantId);
      List<Map<String, String>> stepRows =
          parseSheet(workbook, STEP_SHEET_NAME, STEP_COLUMNS, STEP_REQUIRED_HEADERS, null);
      return new ParsedWorkbook(
          fileNameOrDefault(originalFileName), tenantId, pipelineRows, stepRows);
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
      String defaultTenantId) {
    Sheet sheet = workbook.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet missing: " + sheetName);
    DataFormatter formatter = new DataFormatter();
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
      if (defaultTenantId != null && columns.contains(COL_TENANT_ID)) {
        if (!Texts.hasText(rowValues.get(COL_TENANT_ID))) {
          rowValues.put(COL_TENANT_ID, defaultTenantId);
        }
      }
      rows.add(rowValues);
    }
    return rows;
  }

  private ValidationResult validateRows(ParsedSession session) {
    List<PipelineRow> validPipelines = new ArrayList<>();
    List<ConsolePipelineDefinitionExcelRowIssueResponse> allIssues = new ArrayList<>();
    Set<String> pipelineUniqueKeys = new LinkedHashSet<>();
    int pipelineRowNo = 2;
    for (Map<String, String> rowValues : session.pipelineRows()) {
      List<String> rowIssues = new ArrayList<>();
      PipelineRow row = toPipelineRow(session.tenantId(), pipelineRowNo, rowValues, rowIssues);
      String uniqueKey = row.jobCode() + KEY_SEP_COLON + row.version();
      if (!pipelineUniqueKeys.add(uniqueKey)) {
        rowIssues.add("duplicate pipeline key (job_code + version) in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        validPipelines.add(row);
      } else {
        allIssues.add(
            new ConsolePipelineDefinitionExcelRowIssueResponse(
                PIPELINE_SHEET_NAME, pipelineRowNo, uniqueKey, List.copyOf(rowIssues)));
      }
      pipelineRowNo++;
    }

    List<StepRow> validSteps = new ArrayList<>();
    Set<String> stepUniqueKeys = new LinkedHashSet<>();
    int stepRowNo = 2;
    for (Map<String, String> rowValues : session.stepRows()) {
      List<String> rowIssues = new ArrayList<>();
      StepRow row = toStepRow(stepRowNo, rowValues, rowIssues);
      String parentKey = row.jobCode() + KEY_SEP_COLON + row.version();
      if (!pipelineUniqueKeys.contains(parentKey)) {
        rowIssues.add("no matching pipeline for job_code + version: " + parentKey);
      }
      String stepKey = parentKey + "/" + row.stepCode();
      if (!stepUniqueKeys.add(stepKey)) {
        rowIssues.add("duplicate step_code within pipeline: " + stepKey);
      }
      if (rowIssues.isEmpty()) {
        validSteps.add(row);
      } else {
        allIssues.add(
            new ConsolePipelineDefinitionExcelRowIssueResponse(
                STEP_SHEET_NAME, stepRowNo, stepKey, List.copyOf(rowIssues)));
      }
      stepRowNo++;
    }

    int totalPipelines = session.pipelineRows().size();
    int totalSteps = session.stepRows().size();
    return ValidationResult.builder()
        .pipelineRows(validPipelines)
        .stepRows(validSteps)
        .allIssues(allIssues)
        .totalPipelineRows(totalPipelines)
        .validPipelineRows(validPipelines.size())
        .invalidPipelineRows(totalPipelines - validPipelines.size())
        .totalStepRows(totalSteps)
        .validStepRows(validSteps.size())
        .invalidStepRows(totalSteps - validSteps.size())
        .build();
  }

  private PipelineRow toPipelineRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = normalize(values.get(COL_TENANT_ID));
    if (!Texts.hasText(effectiveTenant)) {
      effectiveTenant = tenantId;
    } else if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return PipelineRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .jobCode(requireText(values, COL_JOB_CODE, 128, issues))
        .pipelineName(requireText(values, COL_PIPELINE_NAME, 256, issues))
        .pipelineType(requireEnum(values, COL_PIPELINE_TYPE, PIPELINE_TYPES, 32, issues))
        .bizType(optionalText(values, COL_BIZ_TYPE, 64, issues))
        .workerGroup(optionalText(values, COL_WORKER_GROUP, 128, issues))
        .version(requireInteger(values, COL_VERSION, 1, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .description(optionalText(values, COL_DESCRIPTION, 512, issues))
        .build();
  }

  private StepRow toStepRow(int rowNo, Map<String, String> values, List<String> issues) {
    return StepRow.builder()
        .rowNo(rowNo)
        .jobCode(requireText(values, COL_JOB_CODE, 128, issues))
        .version(requireInteger(values, COL_VERSION, 1, issues))
        .stepCode(requireText(values, COL_STEP_CODE, 128, issues))
        .stepName(requireText(values, COL_STEP_NAME, 256, issues))
        .stageCode(requireEnum(values, COL_STAGE_CODE, STAGE_CODES, 64, issues))
        .stepOrder(requireInteger(values, COL_STEP_ORDER, 1, issues))
        .implCode(requireText(values, COL_IMPL_CODE, 128, issues))
        .stepParams(optionalJson(values, COL_STEP_PARAMS, issues))
        .timeoutSeconds(requireInteger(values, COL_TIMEOUT_SECONDS, 0, issues))
        .retryPolicy(requireEnum(values, COL_RETRY_POLICY, RETRY_POLICIES, 32, issues))
        .retryMaxCount(requireInteger(values, COL_RETRY_MAX_COUNT, 0, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
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
    if (List.of(GUIDE_FALSE, "N", "0", "NO").contains(upper)) {
      return false;
    }
    issues.add(key + " must be boolean");
    return defaultValue;
  }

  private String optionalJson(Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException exception) {
      issues.add(key + " must be valid JSON");
      return normalized;
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

  private String fileNameOrDefault(String fileName) {
    if (!Texts.hasText(fileName)) {
      return "pipeline-definition.xlsx";
    }
    return fileName;
  }

  private Map<String, Object> buildPipelineInsertParams(String tenantId, PipelineRow row) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(COL_TENANT_ID, tenantId);
    params.put(COL_JOB_CODE, row.jobCode());
    params.put(COL_PIPELINE_NAME, row.pipelineName());
    params.put(COL_PIPELINE_TYPE, row.pipelineType());
    params.put(COL_BIZ_TYPE, row.bizType());
    params.put(COL_WORKER_GROUP, row.workerGroup());
    params.put(COL_VERSION, row.version());
    params.put(COL_ENABLED, row.enabled());
    params.put(COL_DESCRIPTION, row.description());
    return params;
  }

  private Map<String, Object> buildPipelineUpdateParams(String tenantId, Long id, PipelineRow row) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(COL_TENANT_ID, tenantId);
    params.put("id", id);
    params.put(COL_PIPELINE_NAME, row.pipelineName());
    params.put(COL_PIPELINE_TYPE, row.pipelineType());
    params.put(COL_BIZ_TYPE, row.bizType());
    params.put(COL_WORKER_GROUP, row.workerGroup());
    params.put(COL_ENABLED, row.enabled());
    params.put(COL_DESCRIPTION, row.description());
    return params;
  }

  private Map<String, Object> buildStepInsertParams(Long pipelineDefinitionId, StepRow row) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("pipeline_definition_id", pipelineDefinitionId);
    params.put(COL_STEP_CODE, row.stepCode());
    params.put(COL_STEP_NAME, row.stepName());
    params.put(COL_STAGE_CODE, row.stageCode());
    params.put(COL_STEP_ORDER, row.stepOrder());
    params.put(COL_IMPL_CODE, row.implCode());
    params.put(COL_STEP_PARAMS, row.stepParams());
    params.put(COL_TIMEOUT_SECONDS, row.timeoutSeconds());
    params.put(COL_RETRY_POLICY, row.retryPolicy());
    params.put(COL_RETRY_MAX_COUNT, row.retryMaxCount());
    params.put(COL_ENABLED, row.enabled());
    return params;
  }

  private List<PipelineDefinitionDetailResponse> toPipelineResponses(ValidationResult result) {
    Map<String, List<StepRow>> stepsByKey =
        result.stepRows().stream()
            .collect(Collectors.groupingBy(s -> s.jobCode() + KEY_SEP_COLON + s.version()));
    return result.pipelineRows().stream()
        .map(
            pipeline -> {
              String key = pipeline.jobCode() + KEY_SEP_COLON + pipeline.version();
              List<PipelineDefinitionDetailResponse.StepResponse> stepResponses =
                  stepsByKey.getOrDefault(key, List.of()).stream()
                      .map(
                          step ->
                              new PipelineDefinitionDetailResponse.StepResponse(
                                  null,
                                  null,
                                  step.stepCode(),
                                  step.stepName(),
                                  step.stageCode(),
                                  step.stepOrder(),
                                  step.implCode(),
                                  step.stepParams(),
                                  step.timeoutSeconds(),
                                  step.retryPolicy(),
                                  step.retryMaxCount(),
                                  step.enabled(),
                                  null,
                                  null))
                      .toList();
              return new PipelineDefinitionDetailResponse(
                  null,
                  pipeline.tenantId(),
                  pipeline.jobCode(),
                  pipeline.pipelineName(),
                  pipeline.pipelineType(),
                  pipeline.bizType(),
                  pipeline.workerGroup(),
                  pipeline.version(),
                  pipeline.enabled(),
                  pipeline.description(),
                  null,
                  null,
                  stepResponses);
            })
        .toList();
  }

  private void logChange(
      String tenantId,
      PipelineRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("PIPELINE_DEFINITION")
            .withKey(row.jobCode() + KEY_SEP_COLON + row.version())
            .versionNo(row.version())
            .action(action)
            .summary(
                JsonUtils.toJson(
                    mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail",
                            mapOf(
                                "pipelineName", row.pipelineName(),
                                "pipelineType", row.pipelineType(),
                                "bizType", row.bizType(),
                                "workerGroup", row.workerGroup()))))
            .build());
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private record ParsedWorkbook(
      String fileName,
      String tenantId,
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> stepRows) {}

  private record ParsedSession(
      String fileName,
      String tenantId,
      Instant uploadedAt,
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> stepRows) {}

  @Builder
  private record ValidationResult(
      List<PipelineRow> pipelineRows,
      List<StepRow> stepRows,
      List<ConsolePipelineDefinitionExcelRowIssueResponse> allIssues,
      int totalPipelineRows,
      int validPipelineRows,
      int invalidPipelineRows,
      int totalStepRows,
      int validStepRows,
      int invalidStepRows) {}

  @Builder
  private record PipelineRow(
      int rowNo,
      String tenantId,
      String jobCode,
      String pipelineName,
      String pipelineType,
      String bizType,
      String workerGroup,
      Integer version,
      Boolean enabled,
      String description) {}

  @Builder
  private record StepRow(
      int rowNo,
      String jobCode,
      Integer version,
      String stepCode,
      String stepName,
      String stageCode,
      Integer stepOrder,
      String implCode,
      String stepParams,
      Integer timeoutSeconds,
      String retryPolicy,
      Integer retryMaxCount,
      Boolean enabled) {}
}
