package io.github.pinpols.batch.worker.imports.stage.format;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将定长文本文件解析为 NDJSON 记录。 */
public class FixedWidthFormatParser implements FormatParser {

  private static final String ERROR_CODE_FIXED_WIDTH_INVALID = "IMPORT_PARSE_FIXED_INVALID";

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
    int footerRows = support.resolveInt(
        importPayload == null ? null : importPayload.footerRows(),
        templateConfig,
        "footer_rows",
        0);
    int headerRows = support.resolveInt(
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
    FixedWidthParseScope scope =
        new FixedWidthParseScope(context, writer, fields, recordLength, preserveLogicalRow);
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
        parseRecordLine(scope, line, recordNo);
      }
    }
    return recordNo;
  }

  /**
   * 同一次定长解析共享的作业上下文、输出和字段布局。行文本与记录号仍按行传入。
   */
  private record FixedWidthParseScope(
      ImportJobContext context,
      BufferedWriter writer,
      List<FixedWidthField> fields,
      int recordLength,
      boolean preserveLogicalRow) {}

  /** 解析单条记录行：行过短或字段越界按坏行处理，其余切片后写出。 */
  private void parseRecordLine(FixedWidthParseScope scope, String line, long recordNo)
      throws IOException {
    ImportJobContext context = scope.context();
    int recordLength = scope.recordLength();
    if (recordLength > 0 && line.length() < recordLength) {
      support.recordParseError(
          context,
          recordNo,
          ERROR_CODE_FIXED_WIDTH_INVALID,
          "line shorter than record_length",
          line);
      return;
    }
    try {
      Map<String, String> row = sliceFields(scope.fields(), line);
      support.collectSchemaFields(context, row);
      ParseSupport.ParsedRecordWriteParam writeParam = ParseSupport.ParsedRecordWriteParam.builder()
          .context(context)
          .writer(scope.writer())
          .row(row)
          .preserveLogicalRow(scope.preserveLogicalRow())
          .recordNo(recordNo)
          .errorCode(ERROR_CODE_FIXED_WIDTH_INVALID)
          .rawRecord(row)
          .build();
      support.writeParsedRecord(writeParam);
    } catch (Exception exception) {
      SwallowedExceptionLogger.warn(FixedWidthFormatParser.class, "catch:Exception", exception);

      support.recordParseError(
          context, recordNo, ERROR_CODE_FIXED_WIDTH_INVALID, exception.getMessage(), line);
    }
  }

  private static Map<String, String> sliceFields(List<FixedWidthField> fields, String line) {
    Map<String, String> row = new LinkedHashMap<>();
    for (FixedWidthField field : fields) {
      if ((long) field.start() + field.length() > line.length()) {
        throw new IllegalStateException("field overflow: " + field.target());
      }
      String fieldText = line.substring(field.start(), field.start() + field.length());
      row.put(field.target(), fieldText.trim());
    }
    return row;
  }

  private List<FixedWidthField> loadFixedWidthFields(Object fieldMappings) {
    if (!(fieldMappings instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(FixedWidthFormatParser::fixedWidthField).toList();
  }

  private static FixedWidthField fixedWidthField(Object item) {
    if (!(item instanceof Map<?, ?> map)
        || !(map.get("target") instanceof String target)
        || !Texts.hasText(target)) {
      throw new IllegalArgumentException("invalid FIXED_WIDTH field mapping target");
    }
    int start = fixedWidthInteger(map.get("start"));
    int length = fixedWidthInteger(map.get("length"));
    if (start < 0 || length <= 0) {
      throw new IllegalArgumentException("invalid FIXED_WIDTH field mapping range");
    }
    return new FixedWidthField(target, start, length);
  }

  private static int fixedWidthInteger(Object value) {
    if (value instanceof BigInteger integer) {
      try {
        return integer.intValueExact();
      } catch (ArithmeticException invalid) {
        throw new IllegalArgumentException("FIXED_WIDTH value exceeds integer range", invalid);
      }
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      long number = ((Number) value).longValue();
      if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int) number;
    }
    throw new IllegalArgumentException("FIXED_WIDTH value must be a 32-bit integer");
  }

  private record FixedWidthField(String target, int start, int length) {}
}
