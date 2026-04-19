package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.example.batch.common.utils.Texts;

/** Parses fixed-width text files into NDJSON records. */
public class FixedWidthFormatParser implements FormatParser {

  private final ParseSupport support;

  public FixedWidthFormatParser(ParseSupport support) {
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
    int recordLength = support.resolveInt(null, templateConfig, "record_length", 0);
    List<FixedWidthField> fields =
        loadFixedWidthFields(support.templateFieldMappings(templateConfig));
    if (fields.isEmpty()) {
      throw new IllegalStateException(
          "FIXED_WIDTH requires field_mappings with start/length/target");
    }
    long recordNo = 0L;
    List<String> footerBuffer = footerRows > 0 ? new ArrayList<>(footerRows + 1) : List.of();
    try (BufferedReader reader = request.openTextReader()) {
      String line;
      int nonBlankLineNo = 0;
      while ((line = reader.readLine()) != null) {
        if (!Texts.hasText(line)) {
          continue;
        }
        nonBlankLineNo++;
        if (headerRows > 0 && nonBlankLineNo <= headerRows) {
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
        if (recordLength > 0 && line.length() < recordLength) {
          support.recordParseError(
              context,
              recordNo,
              "IMPORT_PARSE_FIXED_INVALID",
              "line shorter than record_length",
              line);
          continue;
        }
        try {
          Map<String, String> row = new LinkedHashMap<>();
          for (FixedWidthField field : fields) {
            if (field.start() + field.length() > line.length()) {
              throw new IllegalStateException("field overflow: " + field.target());
            }
            String value = line.substring(field.start(), field.start() + field.length()).trim();
            row.put(field.target(), value);
          }
          support.collectSchemaFields(context, row);
          support.writeParsedRecord(
              context,
              writer,
              row,
              preserveLogicalRow,
              recordNo,
              "IMPORT_PARSE_FIXED_INVALID",
              row);
        } catch (Exception exception) {
          support.recordParseError(
              context, recordNo, "IMPORT_PARSE_FIXED_INVALID", exception.getMessage(), line);
        }
      }
    }
    return recordNo;
  }

  private List<FixedWidthField> loadFixedWidthFields(Object fieldMappings) {
    if (!(fieldMappings instanceof List<?> list)) {
      return List.of();
    }
    List<FixedWidthField> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        Object target = map.get("target");
        Object start = map.get("start");
        Object length = map.get("length");
        if (target != null && start instanceof Number && length instanceof Number) {
          out.add(
              new FixedWidthField(
                  String.valueOf(target),
                  ((Number) start).intValue(),
                  ((Number) length).intValue()));
        }
      }
    }
    return out;
  }

  private record FixedWidthField(String target, int start, int length) {}
}
