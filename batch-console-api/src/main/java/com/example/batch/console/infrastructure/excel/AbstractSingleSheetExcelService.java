package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.response.excel.ExcelRowIssue;
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

/** 单 sheet Excel 模板与导出基类：export 因查询参数各异由子类实现，复用 {@link #doExport} 生成 workbook。 */
public abstract class AbstractSingleSheetExcelService<ROW, RESP> {

  protected final ConsoleTenantGuard tenantGuard;
  protected final ConsoleRequestMetadataResolver requestMetadataResolver;
  protected final BatchDateTimeSupport dateTimeSupport;
  protected final MessageSource messageSource;

  protected AbstractSingleSheetExcelService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      BatchDateTimeSupport dateTimeSupport,
      MessageSource messageSource) {
    this.tenantGuard = tenantGuard;
    this.requestMetadataResolver = requestMetadataResolver;
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
}
