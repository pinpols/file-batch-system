package com.example.batch.worker.imports.stage.format;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService.SourceLocator;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.FileMagic;
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
 *
 * <p><b>仅支持 .xlsx(OOXML)</b>：本解析器走纯 OOXML 的 {@link OPCPackage}，旧版二进制 .xls(OLE2/HSSF)
 * 喂进来必崩当坏文件。{@code parse} 入口用 {@link FileMagic} 探测字节签名,识别为 OLE2 时 fail-fast 抛 {@link
 * BizException}(明确提示转 .xlsx),而不是静默产出坏数据。
 *
 * <p><b>表头与字段映射</b>:配 {@code field_mappings} 时按 source 表头名投影;LOAD/PARSE 前做表头存在性校验 (缺列 fail-fast
 * 报缺哪列)。未配 {@code field_mappings} 时按 Excel 实际表头名直通(不再硬编码 customer 7 列)。 可选 {@code
 * template_config.excel_sheet_name} 按名选 sheet;{@code template_config.preview_rows} 抽样前 N 行。
 */
public class ExcelFormatParser implements FormatParser {

  private static final int MAX_EXCEL_COLUMNS = 1000;

  private static final long MAX_EXCEL_BYTES =
      Long.getLong("batch.worker.import.max-excel-bytes", 200L * 1024 * 1024);

  // 当 byte[] 大于此阈值,改走"先落 temp file → OPCPackage.open(File)"路径,避免 POI
  // 内部 ByteArrayInputStream → ZipInputStream → 全量缓冲 造成第二份堆内拷贝。
  // 16 MB 与 PreprocessStep 的 SPOOL_THRESHOLD_BYTES 保持一致语义。
  private static final long FILE_BACKED_THRESHOLD_BYTES = 16L * 1024 * 1024;

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
    // Item 1：旧版二进制 .xls(OLE2/HSSF)字节签名 fail-fast。scanner 已不再把 .xls 假映射成 EXCEL,
    // 但若调用方显式 file_format_type=EXCEL 喂进 OLE2 字节,这里给出明确报错而非崩成坏文件。
    rejectLegacyBinaryXls(excelBytes);
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

    int headerRows =
        support.resolveInt(
            importPayload == null ? null : importPayload.headerRows(),
            templateConfig,
            "header_rows",
            1);
    List<ColumnBinding> bindings =
        loadColumnBindings(support.templateFieldMappings(templateConfig));
    long previewRows = resolvePreviewRows(templateConfig);

    Path tempFile = null;
    if (excelBytes.length > FILE_BACKED_THRESHOLD_BYTES) {
      tempFile = Files.createTempFile("import-xlsx-", ".xlsx");
      Files.write(tempFile, excelBytes);
    }
    try (OPCPackage pkg =
        tempFile != null
            ? OPCPackage.open(tempFile.toFile(), PackageAccess.READ)
            : OPCPackage.open(new ByteArrayInputStream(excelBytes))) {
      XSSFReader reader = new XSSFReader(pkg);
      ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
      StylesTable styles = reader.getStylesTable();
      DataFormatter formatter = new DataFormatter();
      XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
      SheetSelector selector = resolveSheetSelector(templateConfig);
      List<String> seenSheetNames = new ArrayList<>();
      int idx = 0;
      while (iter.hasNext()) {
        try (InputStream sheetStream = iter.next()) {
          String sheetName = iter.getSheetName();
          seenSheetNames.add(sheetName);
          if (selector.matches(idx, sheetName)) {
            SaxRowAccumulator accumulator =
                new SaxRowAccumulator(
                    context, writer, preserveLogicalRow, headerRows, bindings, previewRows);
            XMLReader xml = XMLHelper.newXMLReader();
            xml.setContentHandler(
                new XSSFSheetXMLHandler(styles, strings, accumulator, formatter, false));
            try {
              xml.parse(new InputSource(sheetStream));
            } catch (PreviewLimitReachedException stop) {
              // preview_rows 早停信号:已抽样到上限,正常返回已解析的 recordNo。
              SwallowedExceptionLogger.info(
                  ExcelFormatParser.class, "preview row limit reached", stop);
            }
            return accumulator.recordNo;
          }
        }
        idx++;
      }
      // Item 5：按名选 sheet 但没匹配上 → 明确报错列出实际 sheet 名,而非静默返回 0。
      if (selector.byName()) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.import.parse.excel_sheet_not_found",
            selector.sheetName(),
            seenSheetNames.toString());
      }
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException ignore) {
          // best-effort,JVM 退出时 OS 也会清理 /tmp
        }
      }
    }
    return 0L;
  }

  /** OLE2(D0CF11E0...)字节签名 → 旧版二进制 .xls,本解析器不支持,fail-fast 提示转 .xlsx。 */
  private void rejectLegacyBinaryXls(byte[] excelBytes) {
    try (InputStream probe = FileMagic.prepareToCheckMagic(new ByteArrayInputStream(excelBytes))) {
      if (FileMagic.valueOf(probe) == FileMagic.OLE2) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.import.parse.excel_binary_unsupported");
      }
    } catch (IOException ex) {
      // 探测 IO 失败不阻断主流程:交给后续 OPCPackage.open 走原有坏文件路径。
      SwallowedExceptionLogger.warn(ExcelFormatParser.class, "catch:IOException", ex);
    }
  }

  private long resolvePreviewRows(Object templateConfig) {
    int value = support.resolveInt(null, templateConfig, "preview_rows", 0);
    return value <= 0 ? 0L : value;
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
    // Item 4：无 field_mappings 时按 Excel 实际表头名直通(headers 已是实际表头),
    // 不再硬编码 customer 7 列。headers 为空(无表头行)时才回落 defaultHeaders。
    Map<String, String> out = new LinkedHashMap<>();
    List<String> defs = (headers == null || headers.isEmpty()) ? support.defaultHeaders() : headers;
    for (int i = 0; i < defs.size(); i++) {
      String field = defs.get(i);
      String raw = i < cells.size() ? cells.get(i) : null;
      out.put(field, raw == null ? null : raw.trim());
    }
    return out;
  }

  private static int indexOfHeader(List<String> headers, String source) {
    if (headers == null || source == null) {
      return -1;
    }
    // 大小写/下划线容错:与 CSV/LOAD 的 from↔表头匹配规则一致
    String mk = ParseSupport.matchKey(source);
    for (int i = 0; i < headers.size(); i++) {
      if (ParseSupport.matchKey(headers.get(i)).equals(mk)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Item 3：表头存在性校验。配了 {@code field_mappings} 时,每个 source 必须能在实际表头里找到; 缺列即 fail-fast 抛 {@link
   * BizException} 报"缺哪列"——而非 indexOfHeader 返 -1 静默 null 导致整列 null、validate 集体报字段缺失。
   */
  private static void validateHeaders(List<String> headers, List<ColumnBinding> bindings) {
    if (bindings == null || bindings.isEmpty()) {
      return;
    }
    List<String> missing = new ArrayList<>();
    for (ColumnBinding binding : bindings) {
      if (indexOfHeader(headers, binding.source()) < 0) {
        missing.add(binding.source());
      }
    }
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.import.parse.excel_header_missing",
          missing.toString(),
          headers == null ? "[]" : headers.toString());
    }
  }

  private SheetSelector resolveSheetSelector(Object templateConfigObject) {
    // Item 5：优先按名(excelSheetName / excel_sheet_name);否则回落按数字 index(原行为)。
    Map<String, Object> hints = support.parseHints(templateConfigObject);
    String byName = stringHint(hints.get("excelSheetName"));
    if (!Texts.hasText(byName) && templateConfigObject instanceof Map<?, ?> map) {
      byName = stringHint(map.get("excel_sheet_name"));
    }
    if (Texts.hasText(byName)) {
      return SheetSelector.byName(byName.trim());
    }
    return SheetSelector.byIndex(resolveExcelSheetIndex(templateConfigObject));
  }

  private static String stringHint(Object v) {
    return v == null ? null : String.valueOf(v);
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

  /** sheet 选择策略:按 index 或按名。 */
  private record SheetSelector(int index, String sheetName) {
    static SheetSelector byIndex(int index) {
      return new SheetSelector(index, null);
    }

    static SheetSelector byName(String name) {
      return new SheetSelector(-1, name);
    }

    boolean byName() {
      return sheetName != null;
    }

    boolean matches(int idx, String name) {
      if (sheetName != null) {
        return name != null && sheetName.equalsIgnoreCase(name.trim());
      }
      return idx == index;
    }
  }

  /**
   * preview_rows 抽样到上限时,用此异常中断 SAX 流式解析(早停,不全量跑)。 用 unchecked:{@code SheetContentsHandler.endRow}
   * 不允许抛 checked SAXException; RuntimeException 从 ContentHandler 直接穿透 {@code XMLReader.parse}
   * 向上传播,在 parse() 顶层捕获。
   */
  private static final class PreviewLimitReachedException extends RuntimeException {
    PreviewLimitReachedException() {
      super(null, null, false, false);
    }
  }

  /**
   * SAX 行累加器：startRow 清空缓存 → cell 按列号写入 → endRow 时判断 header / blank / record， 写入 downstream。保持和原
   * DOM 版相同的业务语义：headerRows 行做表头、全空行跳过、解析异常 走 {@code recordParseError} 走跳过 / 失败策略。
   *
   * <p>新增:坏行携带 Excel 物理行号(1-based)+ 出错列表头名透传进坏行治理(Item 2); preview_rows &gt; 0 时解析到上限抛 {@link
   * PreviewLimitReachedException} 早停(Item 3)。
   */
  private final class SaxRowAccumulator implements SheetContentsHandler {

    private final ImportJobContext context;
    private final BufferedWriter writer;
    private final boolean preserveLogicalRow;
    private final int headerRows;
    private final List<ColumnBinding> bindings;
    private final long previewRows;
    private final TreeMap<Integer, String> currentRow = new TreeMap<>();
    private int headerRowsSeen = 0;
    private List<String> headers;
    private int currentPhysicalRow = 0;
    long recordNo = 0L;

    SaxRowAccumulator(
        ImportJobContext context,
        BufferedWriter writer,
        boolean preserveLogicalRow,
        int headerRows,
        List<ColumnBinding> bindings,
        long previewRows) {
      this.context = context;
      this.writer = writer;
      this.preserveLogicalRow = preserveLogicalRow;
      this.headerRows = Math.max(headerRows, 0);
      this.bindings = bindings;
      this.previewRows = previewRows;
    }

    @Override
    public void startRow(int rowNum) {
      currentRow.clear();
      // SAX rowNum 为 0-based 物理行号;写入数据库统一 +1 对齐用户在 Excel 里看到的行号。
      currentPhysicalRow = rowNum + 1;
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
          // Item 3：表头解析完成即做存在性校验,缺列 fail-fast(BizException 沿 SAX parse 抛出)。
          validateHeaders(headers, bindings);
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
      } catch (BizException biz) {
        // 表头/sheet 等业务级 fail-fast 不当坏行捕获并抑制,沿 SAX parse 抛出由 ParseStep 转 BUSINESS_ERROR。
        throw biz;
      } catch (Exception ex) {
        SwallowedExceptionLogger.warn(ExcelFormatParser.class, "catch:Exception", ex);
        support.recordParseError(
            context,
            recordNo,
            "IMPORT_PARSE_EXCEL_INVALID",
            ex.getMessage(),
            cells,
            new SourceLocator((long) currentPhysicalRow, resolveErrorColumn()));
      }
      // Item 3：preview 抽样到上限,早停,不全量解析剩余行。
      if (previewRows > 0 && recordNo >= previewRows) {
        throw new PreviewLimitReachedException();
      }
    }

    /** 出错列:配 field_mappings 时无法精确到单列(投影整行),退化为 null;否则取首个非空表头作粗定位。 */
    private String resolveErrorColumn() {
      if (bindings != null && !bindings.isEmpty()) {
        return null;
      }
      if (headers != null && !headers.isEmpty()) {
        String first = headers.get(0);
        return Texts.hasText(first) ? first : null;
      }
      return null;
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
