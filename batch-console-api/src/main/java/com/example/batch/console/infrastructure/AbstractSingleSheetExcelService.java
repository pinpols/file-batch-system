package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ExcelImportStore;
import com.example.batch.console.web.response.ExcelApplyResponse;
import com.example.batch.console.web.response.ExcelPreviewResponse;
import com.example.batch.console.web.response.ExcelQuickImportResponse;
import com.example.batch.console.web.response.ExcelRowIssue;
import com.example.batch.console.web.response.ExcelUploadResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 单 sheet Excel 导入导出的模板基类。
 *
 * <p>统一实现 downloadTemplate / upload / preview / downloadPreviewWorkbook / apply 五个标准端点的样板逻辑，
 * 子类只需提供实体特有的：列定义、行解析、校验规则、upsert 操作和 Response 转换。
 *
 * <p>export 方法因各实体查询参数不同，由子类自行实现并调用 {@link #doExport} 复用 workbook 生成。
 *
 * @param <ROW> 解析后的实体行类型（如 RoutingRow）
 * @param <RESP> 预览时返回的行响应类型（如 ConsoleAlertRoutingResponse）
 */
public abstract class AbstractSingleSheetExcelService<ROW, RESP> {

  protected final ConsoleTenantGuard tenantGuard;
  protected final ConsoleRequestMetadataResolver requestMetadataResolver;
  protected final ExcelImportStore importStore;

  protected AbstractSingleSheetExcelService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore) {
    this.tenantGuard = tenantGuard;
    this.requestMetadataResolver = requestMetadataResolver;
    this.importStore = importStore;
  }

  // 子类必须实现的抽象方法

  /** sheet 名称。 */
  protected abstract String sheetName();

  /** 列名列表（顺序即 Excel 列顺序）。 */
  protected abstract List<String> columns();

  /** 列填写指南（悬停提示）。 */
  protected abstract Map<String, ColumnGuide> columnGuides();

  /** 从 raw map 解析出实体行，校验问题写入 issues。 */
  protected abstract ROW parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues);

  /** 提取行的唯一键（用于去重检测）。 */
  protected abstract String rowUniqueKey(ROW row);

  /** 将解析后的行转换为预览响应 DTO。 */
  protected abstract RESP toResponse(ROW row);

  /**
   * 执行单行 upsert 并返回是否为新增。
   *
   * @return true=新增, false=更新
   */
  protected abstract boolean upsertRow(ROW row, String tenantId, String operatorId);

  /** 记录配置变更日志。 */
  protected abstract void logChange(
      String tenantId, ROW row, String reason, String operatorId, String traceId, String action);

  /** 对 sheet 施加下拉/布尔等数据校验。 */
  protected abstract void applyValidations(Sheet sheet);

  /** 创建 README sheet。 */
  protected abstract void createReadmeSheet(Workbook workbook);

  /** 创建 DICT sheet。 */
  protected abstract void createDictSheet(Workbook workbook);

  // 公共端点实现（子类直接委托）

  /** export 的 workbook 生成 + 下载封装（子类 export 方法调用此方法）。 */
  protected final ResponseEntity<InputStreamResource> doExport(
      String tenantId, List<Map<String, Object>> rows) {
    byte[] bytes = writeWorkbook(rows);
    return ConsoleExcelStyles.excelResponse(bytes, sheetName(), tenantId);
  }

  public final ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] bytes = writeWorkbook(List.of());
    return ConsoleExcelStyles.templateResponse(bytes, sheetName() + "-template.xlsx");
  }

  public final ExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
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
    return new ExcelPreviewResponse<>(
        uploadToken,
        session.fileName(),
        session.sheetName(),
        result.totalRows(),
        result.validRows(),
        result.invalidRows(),
        result.rows().stream().map(this::toResponse).toList(),
        result.issues(),
        previewWorkbookUrl);
  }

  public final ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    Validated<ROW> result = validateRows(session);
    byte[] bytes = writePreviewWorkbook(session, result);
    return ConsoleSingleSheetExcelImportSupport.excelResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()), bytes);
  }

  /**
   * 执行 apply（默认模式：有无效行则拒绝全部）。
   *
   * @see #doApply(String, String, boolean)
   */
  public final ExcelApplyResponse doApply(String uploadToken, String reason) {
    return doApply(uploadToken, reason, false);
  }

  /**
   * 执行 apply。
   *
   * @param skipInvalid true 时跳过无效行，只导入有效行；false 时有无效行则拒绝全部
   */
  public final ExcelApplyResponse doApply(String uploadToken, String reason, boolean skipInvalid) {
    ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
    Validated<ROW> result = validateRows(session);
    if (!skipInvalid && result.invalidRows() > 0) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid rows");
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
    logImportAudit(
        session.tenantId(), session.fileName(), reason, operatorId, traceId,
        inserted, updated, skipInvalid ? result.invalidRows() : 0);
    importStore.remove(uploadToken);
    return new ExcelApplyResponse(
        uploadToken, session.tenantId(), result.rows().size(), inserted, updated,
        skipInvalid ? result.invalidRows() : 0);
  }

  /**
   * 一键导入：upload → validate → 无错误自动 apply / 有错误返回 preview + 错误 workbook URL。
   *
   * <p>前端只需一次调用，替代原来的 upload → preview → apply 三步。
   */
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
   * 记录导入批次审计摘要。默认实现为空（已有逐行 logChange）。子类可覆盖写入批次级审计。
   */
  protected void logImportAudit(
      String tenantId,
      String fileName,
      String reason,
      String operatorId,
      String traceId,
      int inserted,
      int updated,
      int skipped) {
    // 默认空实现 — 逐行变更日志已由 doApply 中的 logChange 记录
  }

  // 校验框架

  protected record Validated<R>(
      int totalRows, int validRows, int invalidRows, List<R> rows, List<ExcelRowIssue> issues) {}

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

  protected static String optionalText(
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

  protected static String requireEnum(
      Map<String, String> values, String key, Set<String> allowed, int maxLength,
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

  protected static Integer requireInteger(
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
    } catch (NumberFormatException e) {
      issues.add(key + " must be integer");
      return min;
    }
  }

  protected static Boolean optionalBoolean(
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

  /** 租户字段解析：空则用当前租户，不匹配则报错。 */
  protected static String resolveTenantField(
      Map<String, String> values, String tenantId, List<String> issues) {
    String effectiveTenant = normalize(values.get("tenant_id"));
    if (!StringUtils.hasText(effectiveTenant)) {
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
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    try {
      JsonUtils.fromJson(normalized, Object.class);
      return normalized;
    } catch (IllegalArgumentException e) {
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
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(sheetName());
      dataSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(dataSheet, columns(), columnGuides(), workbook);
      int rowIndex = 1;
      for (Map<String, Object> row : rows) {
        Row dataRow = dataSheet.createRow(rowIndex++);
        for (int i = 0; i < columns().size(); i++) {
          Cell cell = dataRow.createCell(i);
          Object value = row.get(columns().get(i));
          cell.setCellValue(value == null ? "" : String.valueOf(value));
        }
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, columns());
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      ConsoleExcelStyles.createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
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
              createDictSheet(workbook);
              ConsoleExcelStyles.createValidationSheet(workbook);
            },
            workbookIssues,
            1,
            "failed to generate preview excel workbook"));
  }
}
