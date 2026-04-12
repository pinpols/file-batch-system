package com.example.batch.console.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import org.springframework.util.StringUtils;

/** 单 sheet Excel 导入场景的通用能力：解析 workbook、读取临时会话、生成预览错误 workbook。 */
public final class ConsoleSingleSheetExcelImportSupport {

  private static final MediaType EXCEL_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private ConsoleSingleSheetExcelImportSupport() {}

  public interface SingleSheetImportSession {

    String fileName();

    String tenantId();

    String sheetName();

    Instant uploadedAt();

    List<Map<String, String>> rows();
  }

  public record ParsedWorkbook(
      String fileName, String tenantId, String sheetName, List<Map<String, String>> rows) {}

  public record ParsedSession(
      String fileName,
      String tenantId,
      String sheetName,
      Instant uploadedAt,
      List<Map<String, String>> rows) {}

  public static ResponseEntity<InputStreamResource> excelResponse(
      String fileName, byte[] workbookBytes) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(EXCEL_MEDIA_TYPE)
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  public static ParsedWorkbook parseWorkbook(
      byte[] bytes,
      String tenantId,
      String originalFileName,
      String defaultFileName,
      List<String> columns,
      Set<String> requiredHeaders)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
      }
      Sheet sheet = workbook.getSheetAt(0);
      String sheetName = sheet.getSheetName();
      DataFormatter formatter = new DataFormatter();
      Row headerRow = sheet.getRow(sheet.getFirstRowNum());
      Guard.require(headerRow != null, "excel header row is missing");
      Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
      validateHeaders(headerIndex, requiredHeaders);
      List<Map<String, String>> rows = new ArrayList<>();
      for (int rowIndex = headerRow.getRowNum() + 1;
          rowIndex <= sheet.getLastRowNum();
          rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null || rowIsBlank(row, formatter)) {
          continue;
        }
        Map<String, String> rowValues = new LinkedHashMap<>();
        for (String header : columns) {
          Integer columnIndex = headerIndex.get(header);
          rowValues.put(header, normalize(cellText(row, columnIndex, formatter)));
        }
        if (columns.contains("tenant_id")) {
          rowValues.put(
              "tenant_id",
              StringUtils.hasText(rowValues.get("tenant_id"))
                  ? rowValues.get("tenant_id")
                  : tenantId);
        }
        rows.add(rowValues);
      }
      return new ParsedWorkbook(
          resolveFileName(originalFileName, defaultFileName), tenantId, sheetName, rows);
    } catch (BizException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + exception.getMessage());
    }
  }

  public static ParsedSession loadSession(
      String uploadToken, SingleSheetImportSession session, ConsoleTenantGuard tenantGuard) {
    Guard.requireFound(session, "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return new ParsedSession(
        session.fileName(),
        session.tenantId(),
        session.sheetName(),
        session.uploadedAt(),
        session.rows());
  }

  public static byte[] writePreviewWorkbook(
      ParsedSession session,
      List<String> columns,
      Map<String, ConsoleExcelStyles.ColumnGuide> guides,
      Consumer<Sheet> validationApplier,
      Consumer<Workbook> extraSheetWriter,
      List<WorkbookIssue> issues,
      int fallbackColumnIndex,
      String systemErrorMessage) {
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet dataSheet = workbook.createSheet(session.sheetName());
      dataSheet.createFreezePane(0, 1);
      ConsoleExcelStyles.writeTemplateHeaders(dataSheet, columns, guides, workbook);
      int rowIndex = 1;
      for (Map<String, String> rawRow : session.rows()) {
        Row dataRow = dataSheet.createRow(rowIndex++);
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
          String columnName = columns.get(columnIndex);
          String value = rawRow.get(columnName);
          dataRow
              .createCell(columnIndex)
              .setCellValue(ConsoleExcelStyles.escapeFormula(value == null ? "" : value));
        }
      }
      validationApplier.accept(dataSheet);
      ConsoleExcelStyles.setWidths(dataSheet, columns);
      extraSheetWriter.accept(workbook);
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(workbook, issues);
      ConsoleExcelPreviewWorkbookSupport.addIssueComments(
          dataSheet, columns, issues, fallbackColumnIndex);
      return ConsoleExcelPreviewWorkbookSupport.toBytes(workbook);
    } catch (IOException exception) {
      throw new BizException(ResultCode.SYSTEM_ERROR, systemErrorMessage);
    }
  }

  private static String resolveFileName(String fileName, String defaultFileName) {
    return StringUtils.hasText(fileName) ? fileName : defaultFileName;
  }

  private static Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
    Map<String, Integer> headerIndex = new LinkedHashMap<>();
    for (int cellIndex = headerRow.getFirstCellNum();
        cellIndex < headerRow.getLastCellNum();
        cellIndex++) {
      String header = normalize(formatter.formatCellValue(headerRow.getCell(cellIndex)));
      if (StringUtils.hasText(header)) {
        headerIndex.put(header, cellIndex);
      }
    }
    return headerIndex;
  }

  private static void validateHeaders(
      Map<String, Integer> headerIndex, Set<String> requiredHeaders) {
    List<String> missingHeaders =
        requiredHeaders.stream().filter(header -> !headerIndex.containsKey(header)).toList();
    if (!missingHeaders.isEmpty()) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "excel missing required headers: " + String.join(", ", missingHeaders));
    }
  }

  private static boolean rowIsBlank(Row row, DataFormatter formatter) {
    for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
      String value = normalize(formatter.formatCellValue(row.getCell(cellIndex)));
      if (StringUtils.hasText(value)) {
        return false;
      }
    }
    return true;
  }

  private static String cellText(Row row, Integer columnIndex, DataFormatter formatter) {
    if (columnIndex == null) {
      return null;
    }
    if (row.getCell(columnIndex) == null) {
      return null;
    }
    return formatter.formatCellValue(row.getCell(columnIndex));
  }

  private static String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
