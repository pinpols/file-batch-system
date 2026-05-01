package com.example.batch.worker.imports.stage.format;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * Parses Excel (.xlsx) files into NDJSON records using POI SAX streaming ({@link XSSFReader} +
 * {@link XSSFSheetXMLHandler} event-driven).
 *
 * <p><b>内存模型</b>：不加载整个 workbook 到堆；仅缓存当前行的 cell 值，{@code endRow} 时写入 downstream。{@code
 * SharedStringsTable} 走 {@link ReadOnlySharedStringsTable}（流式解析）。 典型 500MB xlsx 堆占用 ~20MB。
 *
 * <p>尺寸硬限 {@link #MAX_EXCEL_BYTES}（默认 200MB，可用系统属性 {@code batch.worker.import.max-excel-bytes}
 * 覆盖）——SAX 流式下仍保留是因为 sharedStrings 若极端大（每行唯一字符串）仍会占用堆。
 */
public class ExcelFormatParser implements FormatParser {

  private static final int MAX_EXCEL_COLUMNS = 1000;

  private static final long MAX_EXCEL_BYTES =
      Long.getLong("batch.worker.import.max-excel-bytes", 200L * 1024 * 1024);

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
              + MAX_EXCEL_BYTES);
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

    try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(excelBytes))) {
      XSSFReader reader = new XSSFReader(pkg);
      ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
      StylesTable styles = reader.getStylesTable();
      DataFormatter formatter = new DataFormatter();
      XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
      int idx = 0;
      while (iter.hasNext()) {
        try (InputStream sheetStream = iter.next()) {
          if (idx == sheetIndex) {
            SaxRowAccumulator accumulator =
                new SaxRowAccumulator(context, writer, preserveLogicalRow, headerRows, bindings);
            XMLReader xml = XMLHelper.newXMLReader();
            xml.setContentHandler(
                new XSSFSheetXMLHandler(styles, strings, accumulator, formatter, false));
            xml.parse(new InputSource(sheetStream));
            return accumulator.recordNo;
          }
        }
        idx++;
      }
    }
    return 0L;
  }

  /** "A1"→0, "B1"→1, "Z1"→25, "AA1"→26, ...；全数字 / 未知格式 → -1。 */
  private static int columnIndex(String cellReference) {
    if (cellReference == null || cellReference.isEmpty()) {
      return -1;
    }
    int idx = 0;
    int i = 0;
    while (i < cellReference.length()) {
      char c = cellReference.charAt(i);
      if (c < 'A' || c > 'Z') {
        break;
      }
      idx = idx * 26 + (c - 'A' + 1);
      i++;
    }
    return i == 0 ? -1 : idx - 1;
  }

  private Map<String, String> buildRowMapFromCells(
      List<String> cells, List<String> headers, List<ColumnBinding> bindings) {
    if (bindings != null && !bindings.isEmpty()) {
      Map<String, String> out = new LinkedHashMap<>();
      for (ColumnBinding binding : bindings) {
        int idx = indexOfHeader(headers, binding.source());
        if (idx < 0 || idx >= cells.size()) {
          out.put(binding.target(), null);
          continue;
        }
        String raw = cells.get(idx);
        out.put(binding.target(), raw == null ? null : raw.trim());
      }
      return out;
    }
    Map<String, String> out = new LinkedHashMap<>();
    List<String> defs = support.defaultHeaders();
    for (int i = 0; i < defs.size(); i++) {
      String field = defs.get(i);
      String raw = i < cells.size() ? cells.get(i) : null;
      out.put(field, raw == null ? null : raw.trim());
    }
    return out;
  }

  private int indexOfHeader(List<String> headers, String source) {
    if (headers == null) {
      return -1;
    }
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
    if (v != null && Texts.hasText(String.valueOf(v))) {
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

  /**
   * SAX 行累加器：startRow 清空缓存 → cell 按列号写入 → endRow 时判断 header / blank / record， 写入 downstream。保持和原
   * DOM 版相同的业务语义：headerRows 行做表头、全空行跳过、解析异常 走 {@code recordParseError} 走跳过 / 失败策略。
   */
  private final class SaxRowAccumulator implements SheetContentsHandler {

    private final ImportJobContext context;
    private final BufferedWriter writer;
    private final boolean preserveLogicalRow;
    private final int headerRows;
    private final List<ColumnBinding> bindings;
    private final TreeMap<Integer, String> currentRow = new TreeMap<>();
    private int headerRowsSeen = 0;
    private List<String> headers;
    long recordNo = 0L;

    SaxRowAccumulator(
        ImportJobContext context,
        BufferedWriter writer,
        boolean preserveLogicalRow,
        int headerRows,
        List<ColumnBinding> bindings) {
      this.context = context;
      this.writer = writer;
      this.preserveLogicalRow = preserveLogicalRow;
      this.headerRows = Math.max(headerRows, 0);
      this.bindings = bindings;
    }

    @Override
    public void startRow(int rowNum) {
      currentRow.clear();
    }

    @Override
    public void cell(String cellReference, String formattedValue, XSSFComment comment) {
      int col = columnIndex(cellReference);
      if (col < 0 || col >= MAX_EXCEL_COLUMNS) {
        return;
      }
      currentRow.put(col, formattedValue == null ? "" : formattedValue);
    }

    @Override
    public void endRow(int rowNum) {
      if (currentRow.isEmpty()) {
        return;
      }
      int maxCol = currentRow.lastKey();
      List<String> cells = new ArrayList<>(maxCol + 1);
      for (int c = 0; c <= maxCol; c++) {
        cells.add(currentRow.getOrDefault(c, ""));
      }
      if (isAllBlank(cells)) {
        return;
      }
      if (headerRowsSeen < headerRows) {
        headerRowsSeen++;
        if (headerRowsSeen == headerRows) {
          headers = new ArrayList<>(cells);
          while (headers.size() > MAX_EXCEL_COLUMNS) {
            headers.remove(headers.size() - 1);
          }
          if (headers.isEmpty()) {
            headers = support.defaultHeaders();
          }
        }
        return;
      }
      if (headers == null) {
        headers = support.defaultHeaders();
      }
      recordNo++;
      try {
        Map<String, String> rowMap = buildRowMapFromCells(cells, headers, bindings);
        support.collectSchemaFields(context, rowMap);
        ParseSupport.ParsedRecordWriteParam writeParam =
            ParseSupport.ParsedRecordWriteParam.builder()
                .context(context)
                .writer(writer)
                .row(rowMap)
                .preserveLogicalRow(preserveLogicalRow)
                .recordNo(recordNo)
                .errorCode("IMPORT_PARSE_EXCEL_INVALID")
                .rawRecord(rowMap)
                .build();
        support.writeParsedRecord(writeParam);
      } catch (Exception ex) {
        support.recordParseError(
            context, recordNo, "IMPORT_PARSE_EXCEL_INVALID", ex.getMessage(), cells);
      }
    }

    @Override
    public void headerFooter(String text, boolean isHeader, String tagName) {
      // sheet 级 header/footer（打印用）与 batch 无关，忽略
    }

    private boolean isAllBlank(List<String> cells) {
      for (String c : cells) {
        if (c != null && Texts.hasText(c.trim())) {
          return false;
        }
      }
      return true;
    }
  }
}
