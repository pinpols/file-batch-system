package io.github.pinpols.batch.worker.imports.stage.format;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

/** 将 JSON 载荷（数组、信封对象或单个对象）解析为 NDJSON 记录。 */
public class JsonFormatParser implements FormatParser {

  private static final String KEY_RECORDS = "records";
  private static final String ERROR_CODE_JSON_INVALID = "IMPORT_PARSE_JSON_INVALID";
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
        return parseRootObject(context, parser, writer, preserveLogicalRow);
      }
      return parseSingleNode(context, parser, writer, preserveLogicalRow);
    }
  }

  /**
   * 根为对象：records 在首位且是数组时流式解析，否则回退树模型。
   *
   * <p>R2-P0-6 streaming envelope：之前 mapper.readTree(parser) 把整个对象树（含 records 数组）
   * 全量加载进堆；100 MB JSON → ~300-500 MB JsonNode 内存（Jackson tree model 3-5× 膨胀）→ OOM。
   * 现在：peek 第一个字段，若是 "records" + 数组 → 走流式 parseJsonArray 一条一条 readTree；
   * 否则（非典型 envelope 或单对象）回退 readTree 一次性加载，由 ReceiveStep payload size 上限回退。
   */
  private long parseRootObject(
      ImportJobContext context,
      JsonParser parser,
      BufferedWriter writer,
      boolean preserveLogicalRow)
      throws IOException {
    JsonToken firstFieldToken = parser.nextToken();
    if (firstFieldToken == JsonToken.END_OBJECT) {
      return 0L; // 空对象 {}
    }
    if (firstFieldToken == JsonToken.FIELD_NAME
        && KEY_RECORDS.equals(parser.currentName())
        && parser.nextToken() == JsonToken.START_ARRAY) {
      long recordNo = parseJsonArray(context, parser, writer, preserveLogicalRow);
      skipEnvelopeTail(parser);
      return recordNo;
    }
    // 非 records-first envelope / 单对象：回退树模型。payload 安全由 ReceiveStep size 上限保证。
    return parseObjectTree(context, parser, writer, preserveLogicalRow);
  }

  /** 流式跳过 envelope 内 records 之后的兄弟字段（metadata/version 等），不再消耗内存。 */
  private void skipEnvelopeTail(JsonParser parser) throws IOException {
    JsonToken tail = parser.nextToken();
    while (EmptyChecks.isNotNull(tail) && tail != JsonToken.END_OBJECT) {
      if (tail == JsonToken.FIELD_NAME) {
        parser.nextToken(); // 移到 value start
      }
      parser.skipChildren(); // 跳过 value 子树（对标量是 no-op）
      tail = parser.nextToken();
    }
  }

  private long parseObjectTree(
      ImportJobContext context,
      JsonParser parser,
      BufferedWriter writer,
      boolean preserveLogicalRow)
      throws IOException {
    JsonNode rootObj = support.objectMapper().readTree(parser);
    JsonNode recordsNode = EmptyChecks.isNull(rootObj) ? null : rootObj.get(KEY_RECORDS);
    if (EmptyChecks.isNotNull(recordsNode) && recordsNode.isArray()) {
      return writeRecordsNode(context, writer, recordsNode, preserveLogicalRow);
    }
    return writeSingleRecord(context, writer, rootObj, preserveLogicalRow);
  }

  private long writeRecordsNode(
      ImportJobContext context,
      BufferedWriter writer,
      JsonNode recordsNode,
      boolean preserveLogicalRow)
      throws IOException {
    long recordNo = 0L;
    for (JsonNode item : recordsNode) {
      if (EmptyChecks.isNull(item) || item.isNull()) {
        continue;
      }
      recordNo++;
      support.collectSchemaFields(context, item);
      writeJsonRecord(context, writer, item, recordNo, preserveLogicalRow);
    }
    return recordNo;
  }

  /** 根既不是数组也不是对象（标量等）：整体读成一个节点。 */
  private long parseSingleNode(
      ImportJobContext context,
      JsonParser parser,
      BufferedWriter writer,
      boolean preserveLogicalRow)
      throws IOException {
    JsonNode node = support.objectMapper().readTree(parser);
    return writeSingleRecord(context, writer, node, preserveLogicalRow);
  }

  private long writeSingleRecord(
      ImportJobContext context, BufferedWriter writer, JsonNode node, boolean preserveLogicalRow)
      throws IOException {
    if (EmptyChecks.isNull(node) || node.isNull()) {
      return 0L;
    }
    support.collectSchemaFields(context, node);
    writeJsonRecord(context, writer, node, 1L, preserveLogicalRow);
    return 1L;
  }

  private long parseJsonArray(
      ImportJobContext context,
      JsonParser parser,
      BufferedWriter writer,
      boolean preserveLogicalRow)
      throws IOException {
    ObjectMapper mapper = support.objectMapper();
    long recordNo = 0L;
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      JsonNode node = mapper.readTree(parser);
      if (EmptyChecks.isNull(node) || node.isNull()) {
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
      throws IOException {
    ObjectMapper mapper = support.objectMapper();
    try {
      Map<String, Object> row = mapper.convertValue(node, MAP_TYPE);
      ParseSupport.ParsedRecordWriteParam writeParam = ParseSupport.ParsedRecordWriteParam.builder()
          .context(context)
          .writer(writer)
          .row(row)
          .preserveLogicalRow(preserveLogicalRow)
          .recordNo(recordNo)
          .errorCode(ERROR_CODE_JSON_INVALID)
          .rawRecord(node)
          .build();
      support.writeParsedRecord(writeParam);
    } catch (Exception exception) {
      SwallowedExceptionLogger.warn(JsonFormatParser.class, "catch:Exception", exception);

      support.recordParseError(
          context, recordNo, ERROR_CODE_JSON_INVALID, exception.getMessage(), node);
    }
  }
}
