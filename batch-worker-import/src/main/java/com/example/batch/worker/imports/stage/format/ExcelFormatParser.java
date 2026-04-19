package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.util.StringUtils;

/**
 * Parses Excel (.xlsx) files into NDJSON records.
 *
 * <p><b>内存风险</b>：当前用 {@link XSSFWorkbook} 一次性把整个 xlsx 树加载进堆，500MB+ 的 Excel 必
 * OOM。已加 {@link #MAX_EXCEL_BYTES}（默认 50MB，可用系统属性
 * {@code batch.worker.import.max-excel-bytes} 调）硬限作为止血；真正的流式方案（POI {@code XSSFReader}
 * + {@code XSSFSheetXMLHandler} 事件驱动）需独立 PR。
 */
public class ExcelFormatParser implements FormatParser {

  private static final int MAX_EXCEL_COLUMNS = 1000;

  /** Excel 全加载的尺寸硬限：防 POI 一次性构建对象模型撑爆堆。 */
  private static final long MAX_EXCEL_BYTES =
      Long.getLong("batch.worker.import.max-excel-bytes", 50L * 1024 * 1024);

  private final ParseSupport support;

  public ExcelFormatParser(ParseSupport support) {
    this.support = support;
  }

  @Override
  public long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception {
    byte[] excelBytes = request.binaryPayload();
    if (excelBytes == null || excelBytes.length == 0) {
      return 0L;
    }
    if (excelBytes.length > MAX_EXCEL_BYTES) {
      throw new IllegalStateException(
          "IMPORT_PARSE_EXCEL_TOO_LARGE: xlsx size "
              + excelBytes.length
              + " bytes exceeds cap "
              + MAX_EXCEL_BYTES
              + " (XSSFWorkbook is full-memory; flip to SAX streaming to raise this limit)");
    }
    ImportPayload importPayload = request.importPayload();
    Object templateConfig = request.templateConfig();
    boolean preserveLogicalRow = request.preserveLogicalRow();

    int sheetIndex = resolveExcelSheetIndex(templateConfig);
    int headerRows =
        support.resolveInt(
            importPayload == null ? null : importPayload.headerRows(),
            templateConfig,
            "header_rows",
            1);
    List<ColumnBinding> bindings =
        loadColumnBindings(support.templateFieldMappings(templateConfig));

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
      Sheet sheet = workbook.getSheetAt(sheetIndex);
      DataFormatter formatter = new DataFormatter();
      int firstDataRowIndex = Math.max(headerRows, 0);
      List<String> headers;
      if (headerRows > 0) {
        Row headerRow = sheet.getRow(headerRows - 1);
        headers = readExcelHeader(headerRow, formatter);
      } else {
        headers = support.defaultHeaders();
      }
      long recordNo = 0L;
      int lastRow = sheet.getLastRowNum();
      for (int r = firstDataRowIndex; r <= lastRow; r++) {
        Row row = sheet.getRow(r);
        if (row == null || rowIsBlank(row, formatter)) {
          continue;
        }
        recordNo++;
        try {
          Map<String, String> rowMap = buildExcelRowMap(row, headers, bindings, formatter);
          support.collectSchemaFields(context, rowMap);
          support.writeParsedRecord(
              context,
              writer,
              rowMap,
              preserveLogicalRow,
              recordNo,
              "IMPORT_PARSE_EXCEL_INVALID",
              rowMap);
        } catch (Exception exception) {
          support.recordParseError(
              context, recordNo, "IMPORT_PARSE_EXCEL_INVALID", exception.getMessage(), row);
        }
      }
      return recordNo;
    }
  }

  private List<String> readExcelHeader(Row headerRow, DataFormatter formatter) {
    List<String> headers = new ArrayList<>();
    if (headerRow == null) {
      return support.defaultHeaders();
    }
    int lastCell = Math.min(headerRow.getLastCellNum(), MAX_EXCEL_COLUMNS);
    for (int c = 0; c < lastCell; c++) {
      Cell cell = headerRow.getCell(c);
      headers.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
    }
    return headers.isEmpty() ? support.defaultHeaders() : headers;
  }

  private boolean rowIsBlank(Row row, DataFormatter formatter) {
    if (row == null) {
      return true;
    }
    for (int c = 0; c < row.getLastCellNum(); c++) {
      Cell cell = row.getCell(c);
      if (cell != null && StringUtils.hasText(formatter.formatCellValue(cell).trim())) {
        return false;
      }
    }
    return true;
  }

  private Map<String, String> buildExcelRowMap(
      Row row, List<String> headers, List<ColumnBinding> bindings, DataFormatter formatter) {
    if (!bindings.isEmpty()) {
      Map<String, String> out = new LinkedHashMap<>();
      for (ColumnBinding binding : bindings) {
        int idx = indexOfHeader(headers, binding.source());
        if (idx < 0) {
          out.put(binding.target(), null);
          continue;
        }
        Cell cell = row.getCell(idx);
        out.put(binding.target(), cell == null ? null : formatter.formatCellValue(cell).trim());
      }
      return out;
    }
    Map<String, String> out = new LinkedHashMap<>();
    List<String> defs = support.defaultHeaders();
    for (int i = 0; i < defs.size(); i++) {
      String field = defs.get(i);
      Cell cell = i < row.getLastCellNum() ? row.getCell(i) : null;
      out.put(field, cell == null ? null : formatter.formatCellValue(cell).trim());
    }
    return out;
  }

  private int indexOfHeader(List<String> headers, String source) {
    for (int i = 0; i < headers.size(); i++) {
      if (source != null && source.equalsIgnoreCase(headers.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private int resolveExcelSheetIndex(Object templateConfigObject) {
    Map<String, Object> hints = support.parseHints(templateConfigObject);
    Object v = hints.get("excelSheetIndex");
    if (v instanceof Number number) {
      return Math.max(0, number.intValue());
    }
    if (v != null && StringUtils.hasText(String.valueOf(v))) {
      return Math.max(0, Integer.parseInt(String.valueOf(v).trim()));
    }
    if (templateConfigObject instanceof Map<?, ?> map) {
      Object direct = map.get("excel_sheet_index");
      if (direct instanceof Number number) {
        return Math.max(0, number.intValue());
      }
    }
    return 0;
  }

  private List<ColumnBinding> loadColumnBindings(Object fieldMappings) {
    if (!(fieldMappings instanceof List<?> list)) {
      return List.of();
    }
    List<ColumnBinding> bindings = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        Object source = map.get("source");
        Object target = map.get("target");
        if (source != null && target != null) {
          bindings.add(new ColumnBinding(String.valueOf(source), String.valueOf(target)));
        }
      }
    }
    return bindings;
  }

  private record ColumnBinding(String source, String target) {}
}
