package com.example.batch.worker.imports.stage.format;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses delimited text (CSV, TSV, pipe-separated, etc.) into NDJSON records.
 *
 * <p>Backed by Univocity Parsers (RFC 4180 严格解析：正确处理嵌入引号、跨行字段、转义、 BOM、CR/LF 混合换行符)。替换了原手写
 * tokenizer，免费获得边界 case 覆盖。
 */
public class DelimitedFormatParser implements FormatParser {

  private static final String KEY_SCHEMA_FIELDS = "schemaFields";

  private final ParseSupport support;

  public DelimitedFormatParser(ParseSupport support) {
    this.support = support;
  }

  @Override
  public long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception {
    boolean preserveLogicalRow = request.preserveLogicalRow();
    if (!request.hasText()) {
      return 0L;
    }
    ImportPayload importPayload = request.importPayload();
    Object templateConfig = request.templateConfig();
    int footerRows =
        support.resolveInt(
            importPayload == null ? null : importPayload.footerRows(),
            templateConfig,
            "footer_rows",
            0);
    int headerRows =
        support.resolveInt(
            importPayload == null ? null : importPayload.headerRows(),
            templateConfig,
            "header_rows",
            0);
    boolean withHeader =
        importPayload != null && importPayload.withHeader() != null
            ? importPayload.withHeader()
            : headerRows > 0;
    String delimiter = resolveDelimiter(importPayload, templateConfig);

    CsvParser parser = new CsvParser(buildSettings(delimiter));
    // 无表头时按位置绑定:列名取模板 field_mappings 的 name 顺序(模板缺省才回退硬编码示例 schema);
    // 有表头时此初值会被文件首行覆盖(见下方 withHeader 分支)。
    List<String> headers = support.positionalHeaders(templateConfig);
    long recordNo = 0L;
    List<String[]> footerBuffer = footerRows > 0 ? new ArrayList<>(footerRows + 1) : null;

    try (BufferedReader reader = request.openTextReader()) {
      parser.beginParsing(reader);
      String[] row;
      int nonBlankLineNo = 0;
      while ((row = parser.parseNext()) != null) {
        nonBlankLineNo++;
        if ((withHeader || headerRows > 0) && nonBlankLineNo <= Math.max(headerRows, 1)) {
          if (nonBlankLineNo == 1) {
            headers = toHeaders(row);
            // 与 Excel 对齐:配了 field_mappings 时,required 列必须在文件表头里找到——
            // 缺列即 PARSE 期 fail-fast 报"缺哪列",而非静默整列 null 让 VALIDATE 误报"字段缺失"。
            validateRequiredHeaders(headers, templateConfig);
            context.getAttributes().put(KEY_SCHEMA_FIELDS, new ArrayList<>(headers));
          }
          continue;
        }
        if (footerRows > 0) {
          footerBuffer.add(row);
          if (footerBuffer.size() <= footerRows) {
            continue;
          }
          row = footerBuffer.remove(0);
        }
        recordNo++;
        try {
          if (row.length == 0 || isAllBlank(row)) {
            support.recordParseError(
                context, recordNo, "IMPORT_PARSE_LINE_INVALID", "empty line", "");
            continue;
          }
          Map<String, String> rowMap = new LinkedHashMap<>();
          for (int i = 0; i < headers.size(); i++) {
            rowMap.put(headers.get(i), i < row.length ? row[i] : null);
          }
          support.collectSchemaFields(context, rowMap);
          ParseSupport.ParsedRecordWriteParam writeParam =
              ParseSupport.ParsedRecordWriteParam.builder()
                  .context(context)
                  .writer(writer)
                  .row(rowMap)
                  .preserveLogicalRow(preserveLogicalRow)
                  .recordNo(recordNo)
                  .errorCode("IMPORT_PARSE_LINE_INVALID")
                  .rawRecord(rowMap)
                  .build();
          support.writeParsedRecord(writeParam);
        } catch (Exception exception) {
          SwallowedExceptionLogger.warn(DelimitedFormatParser.class, "catch:Exception", exception);

          support.recordParseError(
              context, recordNo, "IMPORT_PARSE_LINE_INVALID", exception.getMessage(), row);
        }
      }
    } finally {
      parser.stopParsing();
    }
    return recordNo;
  }

  private static CsvParserSettings buildSettings(String delimiter) {
    CsvParserSettings settings = new CsvParserSettings();
    settings.getFormat().setDelimiter(delimiter.isEmpty() ? ',' : delimiter.charAt(0));
    settings.getFormat().setQuote('"');
    settings.getFormat().setQuoteEscape('"');
    // headerRows / footerRows / schema fields 由本层按行逻辑处理，不让 Univocity 自动抽 header
    settings.setHeaderExtractionEnabled(false);
    settings.setSkipEmptyLines(true);
    // 任何字段长度 / 列数 的硬限都由 PreprocessStep.SPOOL_THRESHOLD_BYTES / ReceiveStep 的堆联动兜底；
    // 解析器自身放开上限避免宽字段/宽表误拒
    settings.setMaxCharsPerColumn(-1);
    settings.setMaxColumns(10_000);
    // 对齐原手写实现的 `current.toString().trim()` 语义
    settings.trimValues(true);
    return settings;
  }

  private static List<String> toHeaders(String[] row) {
    List<String> headers = new ArrayList<>(row.length);
    for (String value : row) {
      headers.add(value == null ? "" : value);
    }
    return headers;
  }

  /**
   * 表头存在性校验(仅 required 列):配了 {@code field_mappings} 时,每个 {@code required:true} 列的来源名 （{@code
   * name},兼容 {@code source}）必须出现在文件实际表头里;缺列即 fail-fast 报"缺哪列"。 可选列允许缺失。
   */
  private void validateRequiredHeaders(List<String> headers, Object templateConfig) {
    Object fieldMappings = support.templateFieldMappings(templateConfig);
    if (!(fieldMappings instanceof List<?> list) || list.isEmpty()) {
      return;
    }
    List<String> missing = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map) || !isRequired(map.get("required"))) {
        continue;
      }
      Object src = map.get("name") != null ? map.get("name") : map.get("source");
      if (src == null || !Texts.hasText(String.valueOf(src))) {
        continue;
      }
      String source = String.valueOf(src).trim();
      if (!headers.contains(source)) {
        missing.add(source);
      }
    }
    if (!missing.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.import.parse.delimited_header_missing",
          missing.toString(),
          headers.toString());
    }
  }

  private static boolean isRequired(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
  }

  private static boolean isAllBlank(String[] row) {
    for (String value : row) {
      if (value != null && !value.trim().isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private String resolveDelimiter(ImportPayload importPayload, Object templateConfigObject) {
    if (importPayload != null && Texts.hasText(importPayload.delimiter())) {
      return importPayload.delimiter();
    }
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("delimiter");
      if (value != null && Texts.hasText(String.valueOf(value))) {
        return String.valueOf(value);
      }
    }
    return ",";
  }
}
