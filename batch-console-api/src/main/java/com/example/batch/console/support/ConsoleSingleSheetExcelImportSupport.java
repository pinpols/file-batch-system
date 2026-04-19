package com.example.batch.console.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.example.batch.common.utils.Texts;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/** 单 sheet Excel 导入场景的通用能力：解析 workbook、读取临时会话、生成预览错误 workbook。 */
public final class ConsoleSingleSheetExcelImportSupport {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_TENANT_ID = "tenant_id";

  /** 超过此大小（字节）的文件使用 SAX 流式解析，避免大文件 OOM。默认 2 MB。 */
  private static final int SAX_THRESHOLD_BYTES = 2 * 1024 * 1024;

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
    if (bytes.length > SAX_THRESHOLD_BYTES) {
      return parseWorkbookSax(bytes, tenantId, originalFileName, defaultFileName, columns,
          requiredHeaders);
    }
    return parseWorkbookDom(bytes, tenantId, originalFileName, defaultFileName, columns,
        requiredHeaders);
  }

  /** DOM 模式（小文件 ≤ 2MB）：加载整个 Workbook 到内存。 */
  private static ParsedWorkbook parseWorkbookDom(
      byte[] bytes,
      String tenantId,
      String originalFileName,
      String defaultFileName,
      List<String> columns,
      Set<String> requiredHeaders) {
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
        fillTenantDefault(rowValues, columns, tenantId);
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

  /** SAX 流式模式（大文件 > 2MB）：逐行事件驱动解析，内存占用恒定。 */
  private static ParsedWorkbook parseWorkbookSax(
      byte[] bytes,
      String tenantId,
      String originalFileName,
      String defaultFileName,
      List<String> columns,
      Set<String> requiredHeaders) {
    try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(bytes))) {
      ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
      XSSFReader reader = new XSSFReader(pkg);
      StylesTable styles = reader.getStylesTable();

      XSSFReader.SheetIterator sheetIter =
          (XSSFReader.SheetIterator) reader.getSheetsData();
      if (!sheetIter.hasNext()) {
        throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
      }
      String sheetName;
      List<Map<String, String>> rows = new ArrayList<>();
      try (InputStream sheetStream = sheetIter.next()) {
        sheetName = sheetIter.getSheetName();
        SaxSheetHandler handler =
            new SaxSheetHandler(columns, requiredHeaders, tenantId);
        XMLReader xmlReader =
            SAXParserFactory.newDefaultInstance().newSAXParser().getXMLReader();
        xmlReader.setContentHandler(
            new XSSFSheetXMLHandler(styles, strings, handler, new DataFormatter(), false));
        xmlReader.parse(new InputSource(sheetStream));
        rows.addAll(handler.rows());
      }
      return new ParsedWorkbook(
          resolveFileName(originalFileName, defaultFileName), tenantId, sheetName, rows);
    } catch (BizException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "failed to read excel workbook (streaming): " + exception.getMessage());
    }
  }

  private static void fillTenantDefault(
      Map<String, String> rowValues, List<String> columns, String tenantId) {
    if (columns.contains(COL_TENANT_ID)) {
      rowValues.put(
          COL_TENANT_ID,
          Texts.hasText(rowValues.get(COL_TENANT_ID))
              ? rowValues.get(COL_TENANT_ID)
              : tenantId);
    }
  }

  /**
   * SAX 事件处理器：逐行收集单元格值，第一行作为 header 映射，后续行作为数据。
   */
  private static class SaxSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

    private final List<String> columns;
    private final Set<String> requiredHeaders;
    private final String tenantId;

    private Map<String, Integer> headerIndex;
    private final List<Map<String, String>> rows = new ArrayList<>();
    private Map<String, String> currentRow;
    private boolean isHeaderRow = true;
    private final Map<Integer, String> currentCells = new LinkedHashMap<>();

    SaxSheetHandler(List<String> columns, Set<String> requiredHeaders, String tenantId) {
      this.columns = columns;
      this.requiredHeaders = requiredHeaders;
      this.tenantId = tenantId;
    }

    List<Map<String, String>> rows() {
      return rows;
    }

    @Override
    public void startRow(int rowNum) {
      currentCells.clear();
      currentRow = isHeaderRow ? null : new LinkedHashMap<>();
    }

    @Override
    public void endRow(int rowNum) {
      if (isHeaderRow) {
        headerIndex = new LinkedHashMap<>();
        currentCells.forEach((colIdx, value) -> {
          String header = normalize(value);
          if (Texts.hasText(header)) {
            headerIndex.put(header, colIdx);
          }
        });
        validateHeaders(headerIndex, requiredHeaders);
        isHeaderRow = false;
        return;
      }
      if (currentRow != null && !currentCells.isEmpty()) {
        for (String header : columns) {
          Integer colIdx = headerIndex.get(header);
          String cellValue = colIdx != null ? currentCells.get(colIdx) : null;
          currentRow.put(header, normalize(cellValue));
        }
        fillTenantDefault(currentRow, columns, tenantId);
        // 跳过全空行
        boolean allBlank = currentRow.values().stream().noneMatch(Texts::hasText);
        if (!allBlank) {
          rows.add(currentRow);
        }
      }
    }

    @Override
    public void cell(
        String cellReference,
        String formattedValue,
        XSSFComment comment) {
      int colIdx = cellRefToIndex(cellReference);
      currentCells.put(colIdx, formattedValue);
    }

    private static int cellRefToIndex(String cellReference) {
      int col = 0;
      for (int i = 0; i < cellReference.length(); i++) {
        char ch = cellReference.charAt(i);
        if (ch >= 'A' && ch <= 'Z') {
          col = col * 26 + (ch - 'A' + 1);
        } else {
          break;
        }
      }
      return col - 1;
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

  public record WritePreviewWorkbookParam(
      ParsedSession session,
      List<String> columns,
      Map<String, ConsoleExcelStyles.ColumnGuide> guides,
      Consumer<Sheet> validationApplier,
      Consumer<Workbook> extraSheetWriter,
      List<WorkbookIssue> issues,
      int fallbackColumnIndex,
      String systemErrorMessage) {}

  public static byte[] writePreviewWorkbook(WritePreviewWorkbookParam param) {
    ParsedSession session = param.session();
    List<String> columns = param.columns();
    Map<String, ConsoleExcelStyles.ColumnGuide> guides = param.guides();
    Consumer<Sheet> validationApplier = param.validationApplier();
    Consumer<Workbook> extraSheetWriter = param.extraSheetWriter();
    List<WorkbookIssue> issues = param.issues();
    int fallbackColumnIndex = param.fallbackColumnIndex();
    String systemErrorMessage = param.systemErrorMessage();
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet dataSheet = workbook.createSheet(session.sheetName());
      dataSheet.createFreezePane(0, 1, 0, 1);
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
    return Texts.hasText(fileName) ? fileName : defaultFileName;
  }

  private static Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
    Map<String, Integer> headerIndex = new LinkedHashMap<>();
    for (int cellIndex = headerRow.getFirstCellNum();
        cellIndex < headerRow.getLastCellNum();
        cellIndex++) {
      String header = normalize(formatter.formatCellValue(headerRow.getCell(cellIndex)));
      if (Texts.hasText(header)) {
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
      if (Texts.hasText(value)) {
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
    if (!Texts.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
