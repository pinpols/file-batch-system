package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.web.UploadFileGuard;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import com.example.batch.console.web.response.excel.ExcelPreviewResponse;
import com.example.batch.console.web.response.excel.ExcelPreviewResponse.ExcelChangeSummary;
import com.example.batch.console.web.response.excel.ExcelQuickImportResponse;
import com.example.batch.console.web.response.excel.ExcelRowIssue;
import com.example.batch.console.web.response.excel.ExcelUploadResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 单 sheet Excel 导入导出模板基类：export 因查询参数各异由子类实现，复用 {@link #doExport} 生成 workbook。 */
public abstract class AbstractSingleSheetExcelService<ROW, RESP> {

  protected final ConsoleTenantGuard tenantGuard;
  protected final ConsoleRequestMetadataResolver requestMetadataResolver;
  protected final ExcelImportStore importStore;
  protected final BatchDateTimeSupport dateTimeSupport;
  protected final MessageSource messageSource;

  protected AbstractSingleSheetExcelService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchDateTimeSupport dateTimeSupport,
      MessageSource messageSource) {
    this.tenantGuard = tenantGuard;
    this.requestMetadataResolver = requestMetadataResolver;
    this.importStore = importStore;
    this.dateTimeSupport = dateTimeSupport;
    this.messageSource = messageSource;
  }

  protected abstract String sheetName();

  protected abstract List<String> columns();

  protected abstract Map<String, ColumnGuide> columnGuides();

  /** 校验问题写入 issues。 */
  protected abstract ROW parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues);

  protected abstract String rowUniqueKey(ROW row);

  protected abstract RESP toResponse(ROW row);

  /**
   * @return true=新增, false=更新
   */
  protected abstract boolean upsertRow(ROW row, String tenantId, String operatorId);

  protected abstract void logChange(
      String tenantId, ROW row, String reason, String operatorId, String traceId, String action);

  protected boolean rowExists(ROW row, String tenantId) {
    return false;
  }

  protected abstract void applyValidations(Sheet sheet);

  protected abstract void createReadmeSheet(Workbook workbook);

  protected abstract void createDictSheet(Workbook workbook);

  protected void createExtraWorkbookSheets(Workbook workbook, List<Map<String, Object>> rows) {
    // default no-op
  }

  protected final ResponseEntity<InputStreamResource> doExport(
      String tenantId, List<Map<String, Object>> rows) {
    byte[] bytes = writeWorkbook(rows);
    return ConsoleExcelStyles.excelResponse(
        bytes, sheetName(), tenantId, dateTimeSupport.currentFileTimestamp());
  }

  public final ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] bytes = writeWorkbook(List.of());
    return ConsoleExcelStyles.templateResponse(bytes, sheetName() + "-template.xlsx");
  }

  public final ExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    UploadFileGuard.requireExcel(file);
    String tenantId = tenantGuard.resolveTenant(null);
    ConsoleSingleSheetExcelImportSupport.ParsedWorkbook parsed =
        ConsoleSingleSheetExcelImportSupport.parseWorkbook(
            file.getBytes(),
            tenantId,
            file.getOriginalFilename(),
            sheetName() + ".xlsx",
            columns(),
            Set.copyOf(columns()));
    String uploadToken =
        importStore.save(parsed.fileName(), parsed.tenantId(), parsed.sheetName(), parsed.rows());
    return new ExcelUploadResponse(
        uploadToken, parsed.fileName(), parsed.sheetName(), parsed.rows().size());
  }

  public final ExcelPreviewResponse<RESP> preview(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    Validated<ROW> result = validateRows(session);
    String previewWorkbookUrl =
        result.invalidRows() > 0 ? "preview/" + uploadToken + "/workbook" : null;
    return ExcelPreviewResponse.<RESP>builder()
        .uploadToken(uploadToken)
        .fileName(session.fileName())
        .sheetName(session.sheetName())
        .totalRows(result.totalRows())
        .validRows(result.validRows())
        .invalidRows(result.invalidRows())
        .rows(result.rows().stream().map(this::toResponse).toList())
        .issues(result.issues())
        .previewWorkbookUrl(previewWorkbookUrl)
        .changeSummary(summarizeChanges(session.tenantId(), result.rows()))
        .build();
  }

  public final ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    Validated<ROW> result = validateRows(session);
    byte[] bytes = writePreviewWorkbook(session, result);
    return ConsoleSingleSheetExcelImportSupport.excelResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()), bytes);
  }

  public final ExcelApplyResponse doApply(String uploadToken, String reason) {
    return doApply(uploadToken, reason, false);
  }

  /** skipInvalid=true 时只导入有效行；否则有无效行则整体拒绝。 */
  public final ExcelApplyResponse doApply(String uploadToken, String reason, boolean skipInvalid) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    Validated<ROW> result = validateRows(session);
    if (!skipInvalid && result.invalidRows() > 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();
    int inserted = 0;
    int updated = 0;
    for (ROW row : result.rows()) {
      boolean isNew = upsertRow(row, session.tenantId(), operatorId);
      if (isNew) {
        inserted++;
        logChange(session.tenantId(), row, reason, operatorId, traceId, "CREATE");
      } else {
        updated++;
        logChange(session.tenantId(), row, reason, operatorId, traceId, "PUBLISH");
      }
    }
    ImportAuditContext auditCtx =
        ImportAuditContext.builder()
            .tenantId(session.tenantId())
            .fileName(session.fileName())
            .reason(reason)
            .operatorId(operatorId)
            .traceId(traceId)
            .inserted(inserted)
            .updated(updated)
            .skipped(skipInvalid ? result.invalidRows() : 0)
            .build();
    logImportAudit(auditCtx);
    importStore.remove(uploadToken);
    return new ExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        result.rows().size(),
        inserted,
        updated,
        skipInvalid ? result.invalidRows() : 0);
  }

  /** 一键导入：upload → validate → 无错误自动 apply / 有错误返回 preview + 错误 workbook URL。 */
  public final ExcelQuickImportResponse<RESP> quickImport(
      MultipartFile file, String reason, boolean skipInvalid) throws IOException {
    ExcelUploadResponse uploaded = upload(file);
    String uploadToken = uploaded.uploadToken();
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    Validated<ROW> result = validateRows(session);

    boolean canApply = result.invalidRows() == 0 || skipInvalid;
    if (canApply && result.validRows() > 0) {
      ExcelApplyResponse applied = doApply(uploadToken, reason, skipInvalid);
      return new ExcelQuickImportResponse<>(
          true,
          uploadToken,
          uploaded.fileName(),
          result.totalRows(),
          result.validRows(),
          result.invalidRows(),
          applied.insertedRows(),
          applied.updatedRows(),
          applied.skippedRows(),
          result.issues(),
          null);
    }

    // 有错误且不跳过 — 返回 preview 结果和错误 workbook 下载地址
    String previewWorkbookUrl =
        result.invalidRows() > 0 ? "preview/" + uploadToken + "/workbook" : null;
    return new ExcelQuickImportResponse<>(
        false,
        uploadToken,
        uploaded.fileName(),
        result.totalRows(),
        result.validRows(),
        result.invalidRows(),
        0,
        0,
        0,
        result.issues(),
        previewWorkbookUrl);
  }

  /**
   * 默认为空（已有逐行 logChange）；子类可覆盖写入批次级审计。
   *
   * <p>参数封装为 {@link ImportAuditContext} 满足 CLAUDE.md「方法参数 ≤6」硬约束。
   */
  protected void logImportAudit(ImportAuditContext ctx) {
    // 默认空实现 — 逐行变更日志已由 doApply 中的 logChange 记录
  }

  /** 批次级审计参数对象，封装 logImportAudit 的 8 个上下文字段。 */
  @Builder
  public record ImportAuditContext(
      String tenantId,
      String fileName,
      String reason,
      String operatorId,
      String traceId,
      int inserted,
      int updated,
      int skipped) {}

  // 校验框架

  protected record Validated<R>(
      int totalRows, int validRows, int invalidRows, List<R> rows, List<ExcelRowIssue> issues) {}

  private ExcelChangeSummary summarizeChanges(String tenantId, List<ROW> rows) {
    int inserts = 0;
    int updates = 0;
    for (ROW row : rows) {
      if (rowExists(row, tenantId)) {
        updates++;
      } else {
        inserts++;
      }
    }
    return new ExcelChangeSummary(inserts, updates, 0);
  }

  protected final Validated<ROW> validateRows(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session) {
    List<ROW> validRows = new ArrayList<>();
    List<ExcelRowIssue> issues = new ArrayList<>();
    Set<String> uniqueKeys = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> rawRow : session.rows()) {
      List<String> rowIssues = new ArrayList<>();
      ROW row = parseRow(session.tenantId(), rowNo, rawRow, rowIssues);
      String key = rowUniqueKey(row);
      if (!uniqueKeys.add(key)) {
        rowIssues.add("duplicate key in excel: " + key);
      }
      if (rowIssues.isEmpty()) {
        validRows.add(row);
      } else {
        issues.add(new ExcelRowIssue(rowNo, key, key, List.copyOf(rowIssues)));
      }
      rowNo++;
    }
    int total = session.rows().size();
    return new Validated<>(total, validRows.size(), total - validRows.size(), validRows, issues);
  }

  // 字段解析助手（子类在 parseRow 中使用）

  protected static String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  protected static String requireText(
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

  protected static String optionalText(
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

  protected static String requireEnum(
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

  protected static Integer optionalInteger(
      Map<String, String> values, String key, int min, int defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(normalized);
      if (value < min) {
        issues.add(key + " must be >= " + min);
      }
      return value;
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          AbstractSingleSheetExcelService.class, "catch:NumberFormatException", e);

      issues.add(key + " must be integer");
      return defaultValue;
    }
  }

  protected static Integer requireInteger(
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
    } catch (NumberFormatException e) {
      SwallowedExceptionLogger.info(
          AbstractSingleSheetExcelService.class, "catch:NumberFormatException", e);

      issues.add(key + " must be integer");
      return min;
    }
  }

  protected static Boolean optionalBoolean(
      Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
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

  /** 租户字段解析：空则用当前租户，不匹配则报错。 */
  protected static String resolveTenantField(
      Map<String, String> values, String tenantId, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!Texts.hasText(effectiveTenant)) {
      return tenantId;
    }
    if (!tenantId.equals(effectiveTenant)) {
      issues.add("tenant_id must match current tenant: " + tenantId);
    }
    return effectiveTenant;
  }

  /** 构造变更日志用的 Map。 */
  protected static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return map;
  }

  /** 序列化变更摘要 JSON（包含 reason + detail）。 */
  protected static String changeSummaryJson(String reason, Map<String, Object> detail) {
    return JsonUtils.toJson(
        mapOf("reason", ConsoleTextSanitizer.safeInput(reason, 512), "detail", detail));
  }

  /** 可选 JSON 字段：非空时校验 JSON 合法性。 */
  protected static String optionalJson(
      Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException e) {
      SwallowedExceptionLogger.info(
          AbstractSingleSheetExcelService.class, "catch:IllegalArgumentException", e);

      issues.add(key + " must be valid JSON");
      return normalized;
    }
  }

  // Workbook 生成（内部实现）

  private ConsoleSingleSheetExcelImportSupport.ParsedSession loadSession(String uploadToken) {
    return ConsoleSingleSheetExcelImportSupport.loadSession(
        uploadToken, importStore.get(uploadToken), tenantGuard);
  }

  private byte[] writeWorkbook(List<Map<String, Object>> rows) {
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(sheetName());
      dataSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(dataSheet, columns(), columnGuides(), workbook, messageSource, locale);
      int rowIndex = 1;
      for (Map<String, Object> row : rows) {
        Row dataRow = dataSheet.createRow(rowIndex++);
        for (int i = 0; i < columns().size(); i++) {
          Cell cell = dataRow.createCell(i);
          Object value = row.get(columns().get(i));
          cell.setCellValue(
              value == null ? "" : ConsoleExcelStyles.escapeFormula(String.valueOf(value)));
        }
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, columns());
      createExtraWorkbookSheets(workbook, rows);
      createReadmeSheet(workbook);
      ConsoleExcelStyles.createFieldGuideSheet(workbook, sheetName(), columns(), columnGuides());
      createDictSheet(workbook);
      ConsoleExcelStyles.createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR, "error.excel.workbook_generate_failed", e, e.getMessage());
    }
  }

  private byte[] writePreviewWorkbook(
      ConsoleSingleSheetExcelImportSupport.ParsedSession session, Validated<ROW> result) {
    List<WorkbookIssue> workbookIssues =
        result.issues().stream()
            .flatMap(
                issue ->
                    ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        sheetName(), issue.rowNo(), issue.messages(), columns())
                        .stream())
            .toList();
    return ConsoleSingleSheetExcelImportSupport.writePreviewWorkbook(
        new ConsoleSingleSheetExcelImportSupport.WritePreviewWorkbookParam(
            session,
            columns(),
            columnGuides(),
            this::applyValidations,
            workbook -> {
              createReadmeSheet(workbook);
              ConsoleExcelStyles.createFieldGuideSheet(
                  workbook, sheetName(), columns(), columnGuides());
              createDictSheet(workbook);
              ConsoleExcelStyles.createValidationSheet(workbook);
            },
            workbookIssues,
            1,
            "failed to generate preview excel workbook"));
  }
}
