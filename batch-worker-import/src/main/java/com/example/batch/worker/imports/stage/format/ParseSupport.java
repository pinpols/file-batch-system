package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import org.springframework.util.StringUtils;

/** Shared utilities used by all {@link FormatParser} implementations. */
public class ParseSupport {

  static final String KEY_PARSED_COUNT = "parsedCount";
  static final String KEY_SCHEMA_FIELDS = "schemaFields";

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

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

  // ── config resolution ───────────────────────────────────────────────────────

  public Object templateFieldMappings(Object templateConfigObject) {
    if (!(templateConfigObject instanceof Map<?, ?> map)) {
      return null;
    }
    Object fm = map.get("field_mappings");
    if (fm instanceof String text && StringUtils.hasText(text)) {
      try {
        return objectMapper.readValue(text, new TypeReference<List<Object>>() {});
      } catch (Exception ignored) {
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
    if (value instanceof String text && StringUtils.hasText(text)) {
      try {
        return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
      } catch (Exception ignored) {
        return Map.of();
      }
    }
    return Map.of();
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
      if (value != null && StringUtils.hasText(String.valueOf(value))) {
        return Integer.parseInt(String.valueOf(value));
      }
    }
    return defaultValue;
  }

  // ── schema collection ───────────────────────────────────────────────────────

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
        if (item != null && StringUtils.hasText(String.valueOf(item))) {
          fields.add(String.valueOf(item));
        }
      }
    }
    return fields;
  }

  // ── record writing ──────────────────────────────────────────────────────────

  public void writeParsedRecord(
      ImportJobContext context,
      BufferedWriter writer,
      Map<String, ?> row,
      boolean preserveLogicalRow,
      long recordNo,
      String errorCode,
      Object rawRecord)
      throws Exception {
    if (row == null || row.isEmpty()) {
      recordParseError(context, recordNo, errorCode, "parsed row is empty", rawRecord);
      return;
    }
    Object value = row;
    if (!preserveLogicalRow) {
      try {
        CustomerImportPayload payload =
            objectMapper.convertValue(row, CustomerImportPayload.class);
        if (payload == null) {
          recordParseError(
              context, recordNo, errorCode, "record cannot convert to payload", rawRecord);
          return;
        }
        value = payload;
      } catch (RuntimeException ex) {
        if (row == null) {
          throw ex;
        }
        ObjectMapper lenient =
            objectMapper
                .copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CustomerImportPayload payload = lenient.convertValue(row, CustomerImportPayload.class);
        if (payload == null) {
          recordParseError(
              context, recordNo, errorCode, "record cannot convert to payload", rawRecord);
          return;
        }
        value = payload;
      }
    }
    writeNdjsonValue(writer, value);
    writer.newLine();
    context
        .getAttributes()
        .put(KEY_PARSED_COUNT, numberValue(context.getAttributes().get(KEY_PARSED_COUNT)) + 1);
  }

  public void writeNdjsonValue(Writer writer, Object value) throws IOException {
    try (JsonGenerator generator = objectMapper.getFactory().createGenerator(writer)) {
      generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
      objectMapper.writeValue(generator, value);
    }
  }

  // ── error recording ─────────────────────────────────────────────────────────

  public void recordParseError(
      ImportJobContext context, long recordNo, String errorCode, String message, Object rawRecord) {
    if (!recordGovernanceService.isSkippable(errorCode)) {
      recordGovernanceService.recordFailedRecord(
          context, ImportStage.PARSE, recordNo, errorCode, message, rawRecord);
      throw new IllegalStateException(message);
    }
    recordGovernanceService.recordSkippedRecord(
        context, ImportStage.PARSE, recordNo, errorCode, message, rawRecord);
    if (recordGovernanceService.shouldFailOnSkip(errorCode)) {
      throw new IllegalStateException("skip action FAIL_BATCH");
    }
  }

  public boolean withinThreshold(ImportJobContext context) {
    return recordGovernanceService.withinThreshold(context);
  }

  // ── utility ─────────────────────────────────────────────────────────────────

  public long numberValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    String text = String.valueOf(value);
    if (!StringUtils.hasText(text)) {
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
}
