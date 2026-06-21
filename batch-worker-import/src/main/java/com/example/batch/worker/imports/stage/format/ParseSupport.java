package com.example.batch.worker.imports.stage.format;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.PostgresqlJsonbTexts;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/** Shared utilities used by all {@link FormatParser} implementations. */
public class ParseSupport {

  static final String KEY_PARSED_COUNT = PipelineRuntimeKeys.IMPORT_PARSED_COUNT;
  static final String KEY_SCHEMA_FIELDS = PipelineRuntimeKeys.IMPORT_SCHEMA_FIELDS;

  private final ObjectMapper objectMapper;
  private final ImportRecordGovernanceService recordGovernanceService;

  public ParseSupport(
      ObjectMapper objectMapper, ImportRecordGovernanceService recordGovernanceService) {
    this.objectMapper = objectMapper;
    this.recordGovernanceService = recordGovernanceService;
  }

  public ObjectMapper objectMapper() {
    return objectMapper;
  }

  public Object templateFieldMappings(Object templateConfigObject) {
    if (!(templateConfigObject instanceof Map<?, ?> map)) {
      return null;
    }
    Object fm = map.get("field_mappings");
    String text = jsonText(fm);
    if (Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, new TypeReference<List<Object>>() {});
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(ParseSupport.class, "catch:Exception", ignored);

        return null;
      }
    }
    return fm;
  }

  public Map<String, Object> parseHints(Object templateConfigObject) {
    if (!(templateConfigObject instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Object> schemaMap = readJsonObject(map.get("query_param_schema"));
    Object hints = schemaMap.get("parseHints");
    if (!(hints instanceof Map<?, ?> hintMap)) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    hintMap.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  public Map<String, Object> readJsonObject(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    String text = jsonText(value);
    if (text != null && Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(ParseSupport.class, "catch:Exception", ignored);

        return Map.of();
      }
    }
    return Map.of();
  }

  private static String jsonText(Object value) {
    if (value instanceof String s) {
      return s;
    }
    return PostgresqlJsonbTexts.tryExtract(value);
  }

  public int resolveInt(
      Integer overrideValue, Object templateConfigObject, String key, int defaultValue) {
    if (overrideValue != null) {
      return overrideValue;
    }
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get(key);
      if (value instanceof Number number) {
        return number.intValue();
      }
      if (value != null && Texts.hasText(String.valueOf(value))) {
        return Integer.parseInt(String.valueOf(value));
      }
    }
    return defaultValue;
  }

  public void collectSchemaFields(ImportJobContext context, JsonNode node) {
    if (context == null || node == null || !node.isObject()) {
      return;
    }
    LinkedHashSet<String> fields = existingSchemaFields(context);
    node.fieldNames().forEachRemaining(fields::add);
    context.getAttributes().put(KEY_SCHEMA_FIELDS, new ArrayList<>(fields));
  }

  public void collectSchemaFields(ImportJobContext context, Map<String, ?> row) {
    if (context == null || row == null || row.isEmpty()) {
      return;
    }
    LinkedHashSet<String> fields = existingSchemaFields(context);
    row.keySet().forEach(fields::add);
    context.getAttributes().put(KEY_SCHEMA_FIELDS, new ArrayList<>(fields));
  }

  private LinkedHashSet<String> existingSchemaFields(ImportJobContext context) {
    Object existing = context.getAttributes().get(KEY_SCHEMA_FIELDS);
    LinkedHashSet<String> fields = new LinkedHashSet<>();
    if (existing instanceof List<?> list) {
      for (Object item : list) {
        if (item != null && Texts.hasText(String.valueOf(item))) {
          fields.add(String.valueOf(item));
        }
      }
    }
    return fields;
  }

  public void writeParsedRecord(ParsedRecordWriteParam param) throws Exception {
    if (param.row() == null || param.row().isEmpty()) {
      recordParseError(
          param.context(),
          param.recordNo(),
          param.errorCode(),
          "parsed row is empty",
          param.rawRecord());
      return;
    }
    // 参数 preserveLogicalRow 保留是为了调用方的 API 兼容，但内部不再区分：
    // 历史上 false 分支把 row 强转 CustomerImportPayload，导致所有非 customer schema 的 import
    // 被默默丢字段 → validate 阶段集体报 "customerNo is required"。
    // 统一成 Map → NDJSON 后，LoadStep 的流式路径本来就按 Map 读 NDJSON（MAP_TYPE），无行为变化；
    // 单 schema 路径由 jdbc_mapped_import 的 columnMappings 接管字段投影。
    writeNdjsonValue(param.writer(), param.row());
    param.writer().newLine();
    param
        .context()
        .getAttributes()
        .put(
            KEY_PARSED_COUNT,
            numberValue(param.context().getAttributes().get(KEY_PARSED_COUNT)) + 1);
  }

  @Builder
  public record ParsedRecordWriteParam(
      ImportJobContext context,
      BufferedWriter writer,
      Map<String, ?> row,
      boolean preserveLogicalRow,
      long recordNo,
      String errorCode,
      Object rawRecord) {}

  public void writeNdjsonValue(Writer writer, Object value) throws IOException {
    try (JsonGenerator generator = objectMapper.getFactory().createGenerator(writer)) {
      generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
      objectMapper.writeValue(generator, value);
    }
  }

  public void recordParseError(
      ImportJobContext context, long recordNo, String errorCode, String message, Object rawRecord) {
    recordParseError(context, recordNo, errorCode, message, rawRecord, null);
  }

  /**
   * 带 Excel 物理定位({@link ImportRecordGovernanceService.SourceLocator})的 PARSE 阶段坏行记录。 locator
   * 为空时退化为不带定位的行为(其余格式 / 无法定位场景)。
   */
  public void recordParseError(
      ImportJobContext context,
      long recordNo,
      String errorCode,
      String message,
      Object rawRecord,
      ImportRecordGovernanceService.SourceLocator locator) {
    ImportRecordGovernanceService.BadRecordCommand command =
        ImportRecordGovernanceService.BadRecordCommand.of(
            ImportStage.PARSE, recordNo, errorCode, message, rawRecord, locator);
    if (!recordGovernanceService.isSkippable(errorCode)) {
      recordGovernanceService.recordFailedRecord(context, command);
      throw new IllegalStateException(message);
    }
    recordGovernanceService.recordSkippedRecord(context, command);
    if (recordGovernanceService.shouldFailOnSkip(errorCode)) {
      throw new IllegalStateException("skip action FAIL_BATCH");
    }
  }

  public boolean withinThreshold(ImportJobContext context) {
    return recordGovernanceService.withinThreshold(context);
  }

  public void markThresholdExceeded(ImportJobContext context) {
    recordGovernanceService.markThresholdExceeded(context);
  }

  public long numberValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    String text = String.valueOf(value);
    if (!Texts.hasText(text)) {
      return 0L;
    }
    return Long.parseLong(text);
  }

  public List<String> defaultHeaders() {
    return List.of(
        "customerNo",
        "customerName",
        "customerType",
        "certificateNo",
        "mobileNo",
        "email",
        "status");
  }

  /**
   * 表头/字段名的容错匹配键:全小写 + 去掉下划线/连字符/空格。让 {@code Phone}/{@code PHONE}/{@code phone} 与 {@code
   * mobile_no}/{@code mobileNo} 这类纯写法差异在匹配时等价——用于 {@code from}↔文件表头 的大小写/分隔符容错。
   */
  public static String matchKey(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '_' || c == '-' || c == ' ') {
        continue;
      }
      sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
  }

  /** 无表头分隔文件的位置列名:取 field_mappings 的 name 顺序,缺省回退 defaultHeaders。 */
  public List<String> positionalHeaders(Object templateConfigObject) {
    Object fieldMappings = templateFieldMappings(templateConfigObject);
    if (fieldMappings instanceof List<?> list) {
      List<String> names = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          Object name = m.get("name");
          if (name != null && Texts.hasText(String.valueOf(name))) {
            names.add(String.valueOf(name).trim());
          }
        }
      }
      if (!names.isEmpty()) {
        return names;
      }
    }
    return defaultHeaders();
  }
}
