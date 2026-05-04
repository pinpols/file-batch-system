package com.example.batch.worker.imports.stage.format;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.Map;

/** Parses JSON payloads (array, envelope, single object) into NDJSON records. */
public class JsonFormatParser implements FormatParser {

  private static final String KEY_RECORDS = "records";
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ParseSupport support;

  public JsonFormatParser(ParseSupport support) {
    this.support = support;
  }

  @Override
  public long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception {
    boolean preserveLogicalRow = request.preserveLogicalRow();
    if (!request.hasText()) {
      return 0L;
    }
    ObjectMapper mapper = support.objectMapper();
    try (BufferedReader textReader = request.openTextReader();
        JsonParser parser = mapper.getFactory().createParser(textReader)) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        return 0L;
      }
      if (token == JsonToken.START_ARRAY) {
        return parseJsonArray(context, parser, writer, preserveLogicalRow);
      }
      if (token == JsonToken.START_OBJECT) {
        JsonNode rootObj = mapper.readTree(parser);
        JsonNode recordsNode = rootObj == null ? null : rootObj.get(KEY_RECORDS);
        if (recordsNode != null && recordsNode.isArray()) {
          long recordNo = 0L;
          for (JsonNode record : recordsNode) {
            if (record == null || record.isNull()) {
              continue;
            }
            recordNo++;
            support.collectSchemaFields(context, record);
            writeJsonRecord(context, writer, record, recordNo, preserveLogicalRow);
          }
          return recordNo;
        }
        if (rootObj == null || rootObj.isNull()) {
          return 0L;
        }
        support.collectSchemaFields(context, rootObj);
        writeJsonRecord(context, writer, rootObj, 1L, preserveLogicalRow);
        return 1L;
      }
      JsonNode node = mapper.readTree(parser);
      if (node == null || node.isNull()) {
        return 0L;
      }
      support.collectSchemaFields(context, node);
      writeJsonRecord(context, writer, node, 1L, preserveLogicalRow);
      return 1L;
    }
  }

  private long parseJsonArray(
      ImportJobContext context,
      JsonParser parser,
      BufferedWriter writer,
      boolean preserveLogicalRow)
      throws Exception {
    ObjectMapper mapper = support.objectMapper();
    long recordNo = 0L;
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      JsonNode node = mapper.readTree(parser);
      if (node == null || node.isNull()) {
        continue;
      }
      recordNo++;
      support.collectSchemaFields(context, node);
      writeJsonRecord(context, writer, node, recordNo, preserveLogicalRow);
    }
    return recordNo;
  }

  private void writeJsonRecord(
      ImportJobContext context,
      BufferedWriter writer,
      JsonNode node,
      long recordNo,
      boolean preserveLogicalRow)
      throws Exception {
    ObjectMapper mapper = support.objectMapper();
    try {
      Map<String, Object> row = mapper.convertValue(node, MAP_TYPE);
      ParseSupport.ParsedRecordWriteParam writeParam =
          ParseSupport.ParsedRecordWriteParam.builder()
              .context(context)
              .writer(writer)
              .row(row)
              .preserveLogicalRow(preserveLogicalRow)
              .recordNo(recordNo)
              .errorCode("IMPORT_PARSE_JSON_INVALID")
              .rawRecord(node)
              .build();
      support.writeParsedRecord(writeParam);
    } catch (Exception exception) {
      SwallowedExceptionLogger.warn(JsonFormatParser.class, "catch:Exception", exception);

      support.recordParseError(
          context, recordNo, "IMPORT_PARSE_JSON_INVALID", exception.getMessage(), node);
    }
  }
}
