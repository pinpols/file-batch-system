package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleTenantQuotaPolicyExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.TenantQuotaPolicyMapper;
import com.example.batch.console.mapper.param.TenantQuotaPolicyUpsertParam;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.TenantQuotaPolicyExcelImportStore;
import com.example.batch.console.web.request.TenantQuotaPolicyExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleTenantQuotaPolicyResponse;
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

/** {@link ConsoleTenantQuotaPolicyExcelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleTenantQuotaPolicyExcelApplicationService
    implements ConsoleTenantQuotaPolicyExcelApplicationService {

  private static final String SHEET_NAME = "tenant_quota_policy";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "policy_code",
          "max_running_jobs_per_tenant",
          "max_partitions_per_tenant",
          "max_qps_per_tenant",
          "fair_share_weight",
          "enabled",
          "description");
  private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", "字符串", "tenant-a")),
          Map.entry("policy_code", requiredColumn("策略唯一编码，作为导入匹配键。", "字符串", "DEFAULT_POLICY")),
          Map.entry(
              "max_running_jobs_per_tenant", requiredColumn("租户最大并行作业数，必须 >= 0。", "整数", "10")),
          Map.entry("max_partitions_per_tenant", requiredColumn("租户最大分区数，必须 >= 0。", "整数", "100")),
          Map.entry("max_qps_per_tenant", requiredColumn("租户最大 QPS，必须 >= 0。", "整数", "50")),
          Map.entry("fair_share_weight", requiredColumn("公平调度权重，必须 >= 1。", "整数", "1")),
          Map.entry("enabled", optionalColumn("策略是否启用，默认 TRUE。", "布尔值", "TRUE", "TRUE", "FALSE")),
          Map.entry("description", optionalColumn("策略描述信息。", "字符串", "默认配额策略")));

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final TenantQuotaPolicyExcelImportStore importStore;

  @Override
  public ResponseEntity<InputStreamResource> exportQuotaPolicies(
      String tenantId, String policyCode, Boolean enabled) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> rows =
        tenantQuotaPolicyMapper.selectByQuery(resolvedTenantId, policyCode, enabled, null);
    byte[] workbookBytes = writeWorkbook(rows);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName =
        "tenant-quota-policy-" + resolvedTenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
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
                .filename("tenant-quota-policy-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleTenantQuotaPolicyExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ConsoleSingleSheetExcelImportSupport.ParsedWorkbook parsedWorkbook =
        ConsoleSingleSheetExcelImportSupport.parseWorkbook(
            file.getBytes(),
            tenantId,
            file.getOriginalFilename(),
            "tenant-quota-policy.xlsx",
            COLUMNS,
            REQUIRED_HEADERS);
    String uploadToken =
        importStore.save(
            parsedWorkbook.fileName(),
            parsedWorkbook.tenantId(),
            parsedWorkbook.sheetName(),
            parsedWorkbook.rows());
    return new ConsoleTenantQuotaPolicyExcelUploadResponse(
        uploadToken,
        parsedWorkbook.fileName(),
        parsedWorkbook.sheetName(),
        parsedWorkbook.rows().size());
  }

  @Override
  public ConsoleTenantQuotaPolicyExcelPreviewResponse preview(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    return new ConsoleTenantQuotaPolicyExcelPreviewResponse(
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
  public ConsoleTenantQuotaPolicyExcelApplyResponse apply(
      String uploadToken, TenantQuotaPolicyExcelApplyRequest request) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validateRows(session);
    if (validationResult.invalidRows() > 0) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "excel contains invalid quota policy rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();
    int inserted = 0;
    int updated = 0;
    for (PolicyRow row : validationResult.rows()) {
      Map<String, Object> existing =
          tenantQuotaPolicyMapper.selectByUniqueKey(session.tenantId(), row.policyCode());
      TenantQuotaPolicyUpsertParam param = new TenantQuotaPolicyUpsertParam();
      param.setTenantId(session.tenantId());
      param.setPolicyCode(row.policyCode());
      param.setMaxRunningJobsPerTenant(row.maxRunningJobsPerTenant());
      param.setMaxPartitionsPerTenant(row.maxPartitionsPerTenant());
      param.setMaxQpsPerTenant(row.maxQpsPerTenant());
      param.setFairShareWeight(row.fairShareWeight());
      param.setEnabled(row.enabled());
      param.setDescription(row.description());
      tenantQuotaPolicyMapper.upsertTenantQuotaPolicy(param);
      if (existing == null || existing.isEmpty()) {
        inserted++;
        logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "CREATE");
      } else {
        updated++;
        logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "PUBLISH");
      }
    }
    importStore.remove(uploadToken);
    return new ConsoleTenantQuotaPolicyExcelApplyResponse(
        uploadToken, session.tenantId(), validationResult.rows().size(), inserted, updated);
  }

  private ConsoleSingleSheetExcelImportSupport.ParsedSession loadSession(String uploadToken) {
    return ConsoleSingleSheetExcelImportSupport.loadSession(
        uploadToken, importStore.get(uploadToken), tenantGuard);
  }

  private ValidationResult validateRows(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session) {
    List<PolicyRow> rows = new ArrayList<>();
    List<ConsoleTenantQuotaPolicyExcelRowIssueResponse> issues = new ArrayList<>();
    Set<String> uniqueKeys = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> rowValues : session.rows()) {
      List<String> rowIssues = new ArrayList<>();
      PolicyRow row = toPolicyRow(session.tenantId(), rowNo, rowValues, rowIssues);
      String uniqueKey = row.policyCode();
      if (!uniqueKeys.add(uniqueKey)) {
        rowIssues.add("duplicate policy code in excel: " + uniqueKey);
      }
      if (rowIssues.isEmpty()) {
        rows.add(row);
      } else {
        issues.add(
            new ConsoleTenantQuotaPolicyExcelRowIssueResponse(
                rowNo, uniqueKey, row.policyCode(), List.copyOf(rowIssues)));
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

  private PolicyRow toPolicyRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!StringUtils.hasText(effectiveTenant)) {
      effectiveTenant = tenantId;
    } else if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return PolicyRow.builder()
        .identity(
            PolicyIdentity.builder()
                .rowNo(rowNo)
                .tenantId(effectiveTenant)
                .policyCode(requireText(values, "policy_code", 128, issues))
                .build())
        .quota(
            PolicyQuota.builder()
                .maxRunningJobsPerTenant(
                    requireInteger(values, "max_running_jobs_per_tenant", 0, issues))
                .maxPartitionsPerTenant(
                    requireInteger(values, "max_partitions_per_tenant", 0, issues))
                .maxQpsPerTenant(requireInteger(values, "max_qps_per_tenant", 0, issues))
                .fairShareWeight(requireInteger(values, "fair_share_weight", 1, issues))
                .build())
        .state(
            PolicyState.builder()
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
    addBooleanValidation(sheet, new int[] {6}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "tenant quota policy maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. policy_code is the unique key used during preview and apply.",
      "3. enabled has built-in dropdown validation (TRUE / FALSE).",
      "4. All integer fields must be >= 0, fair_share_weight must be >= 1.",
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
      PolicyRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "TENANT_QUOTA_POLICY",
            "configKey",
            row.policyCode(),
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
                            "maxRunningJobsPerTenant", row.maxRunningJobsPerTenant(),
                            "maxPartitionsPerTenant", row.maxPartitionsPerTenant(),
                            "maxQpsPerTenant", row.maxQpsPerTenant(),
                            "fairShareWeight", row.fairShareWeight())))));
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private ConsoleTenantQuotaPolicyResponse toResponse(PolicyRow row) {
    return new ConsoleTenantQuotaPolicyResponse(
        null,
        row.tenantId(),
        row.policyCode(),
        row.maxRunningJobsPerTenant(),
        row.maxPartitionsPerTenant(),
        row.maxQpsPerTenant(),
        row.fairShareWeight(),
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

    List<PolicyRow> rows() {
      return data.rows();
    }

    List<ConsoleTenantQuotaPolicyExcelRowIssueResponse> issues() {
      return data.issues();
    }
  }

  @Builder
  private record ValidationCounts(int totalRows, int validRows, int invalidRows) {}

  @Builder
  private record ValidationData(
      List<PolicyRow> rows, List<ConsoleTenantQuotaPolicyExcelRowIssueResponse> issues) {}

  @Builder
  private record PolicyRow(PolicyIdentity identity, PolicyQuota quota, PolicyState state) {
    int rowNo() {
      return identity.rowNo();
    }

    String tenantId() {
      return identity.tenantId();
    }

    String policyCode() {
      return identity.policyCode();
    }

    Integer maxRunningJobsPerTenant() {
      return quota.maxRunningJobsPerTenant();
    }

    Integer maxPartitionsPerTenant() {
      return quota.maxPartitionsPerTenant();
    }

    Integer maxQpsPerTenant() {
      return quota.maxQpsPerTenant();
    }

    Integer fairShareWeight() {
      return quota.fairShareWeight();
    }

    Boolean enabled() {
      return state.enabled();
    }

    String description() {
      return state.description();
    }
  }

  @Builder
  private record PolicyIdentity(int rowNo, String tenantId, String policyCode) {}

  @Builder
  private record PolicyQuota(
      Integer maxRunningJobsPerTenant,
      Integer maxPartitionsPerTenant,
      Integer maxQpsPerTenant,
      Integer fairShareWeight) {}

  @Builder
  private record PolicyState(Boolean enabled, String description) {}
}
