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
        // R2-P0-6 streaming envelope：之前 mapper.readTree(parser) 把整个对象树（含 records 数组）
        // 全量加载进堆；100 MB JSON → ~300-500 MB JsonNode 内存（Jackson tree model 3-5× 膨胀）→ OOM。
        // 现在：peek 第一个字段，若是 "records" + 数组 → 走流式 parseJsonArray 一条一条 readTree；
        // 否则（非典型 envelope 或单对象）回退 readTree 一次性加载，由 ReceiveStep payload size 上限回退。
        JsonToken firstFieldToken = parser.nextToken();
        if (firstFieldToken == JsonToken.END_OBJECT) {
          return 0L; // 空对象 {}
        }
        if (firstFieldToken == JsonToken.FIELD_NAME && KEY_RECORDS.equals(parser.currentName())) {
          JsonToken arrayStart = parser.nextToken();
          if (arrayStart == JsonToken.START_ARRAY) {
            long recordNo = parseJsonArray(context, parser, writer, preserveLogicalRow);
            // 流式跳过 envelope 内 records 之后的兄弟字段（metadata/version 等），不再消耗内存
            JsonToken tail;
            while ((tail = parser.nextToken()) != null && tail != JsonToken.END_OBJECT) {
              if (tail == JsonToken.FIELD_NAME) {
                parser.nextToken(); // 移到 value start
              }
              parser.skipChildren(); // 跳过 value 子树（对标量是 no-op）
            }
            return recordNo;
          }
        }
        // 非 records-first envelope / 单对象：回退树模型。payload 安全由 ReceiveStep size 上限保证。
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
