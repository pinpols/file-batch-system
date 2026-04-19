package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Parses delimited text (CSV, TSV, pipe-separated, etc.) into NDJSON records. */
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
    List<String> headers = support.defaultHeaders();
    long recordNo = 0L;
    List<String> footerBuffer = footerRows > 0 ? new ArrayList<>(footerRows + 1) : List.of();
    try (BufferedReader reader = request.openTextReader()) {
      String line;
      int nonBlankLineNo = 0;
      while ((line = reader.readLine()) != null) {
        if (!StringUtils.hasText(line)) {
          continue;
        }
        nonBlankLineNo++;
        if ((withHeader || headerRows > 0) && nonBlankLineNo <= Math.max(headerRows, 1)) {
          if (nonBlankLineNo == 1) {
            headers = parseDelimitedLine(line, delimiter);
            context.getAttributes().put(KEY_SCHEMA_FIELDS, new ArrayList<>(headers));
          }
          continue;
        }
        if (footerRows > 0) {
          footerBuffer.add(line);
          if (footerBuffer.size() <= footerRows) {
            continue;
          }
          line = footerBuffer.remove(0);
        }
        recordNo++;
        try {
          List<String> columns = parseDelimitedLine(line, delimiter);
          if (columns.isEmpty()) {
            support.recordParseError(
                context, recordNo, "IMPORT_PARSE_LINE_INVALID", "empty line", line);
            continue;
          }
          Map<String, String> row = new LinkedHashMap<>();
          for (int i = 0; i < headers.size(); i++) {
            row.put(headers.get(i), i < columns.size() ? columns.get(i) : null);
          }
          support.collectSchemaFields(context, row);
          support.writeParsedRecord(
              context,
              writer,
              row,
              preserveLogicalRow,
              recordNo,
              "IMPORT_PARSE_LINE_INVALID",
              row);
        } catch (Exception exception) {
          support.recordParseError(
              context, recordNo, "IMPORT_PARSE_LINE_INVALID", exception.getMessage(), line);
        }
      }
    }
    return recordNo;
  }

  private String resolveDelimiter(ImportPayload importPayload, Object templateConfigObject) {
    if (importPayload != null && StringUtils.hasText(importPayload.delimiter())) {
      return importPayload.delimiter();
    }
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("delimiter");
      if (value != null && StringUtils.hasText(String.valueOf(value))) {
        return String.valueOf(value);
      }
    }
    return ",";
  }

  private List<String> parseDelimitedLine(String line, String delimiter) {
    List<String> columns = new ArrayList<>();
    if (line == null) {
      return columns;
    }
    char delimiterChar = delimiter.charAt(0);
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char currentChar = line.charAt(i);
      if (currentChar == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
        continue;
      }
      if (!quoted && currentChar == delimiterChar) {
        columns.add(current.toString().trim());
        current.setLength(0);
        continue;
      }
      current.append(currentChar);
    }
    columns.add(current.toString().trim());
    return columns;
  }
}
