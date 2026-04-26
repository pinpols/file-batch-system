package com.example.batch.worker.imports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.utils.EncodingUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.example.batch.worker.imports.stage.format.DelimitedFormatParser;
import com.example.batch.worker.imports.stage.format.ExcelFormatParser;
import com.example.batch.worker.imports.stage.format.FixedWidthFormatParser;
import com.example.batch.worker.imports.stage.format.FormatParseRequest;
import com.example.batch.worker.imports.stage.format.FormatParser;
import com.example.batch.worker.imports.stage.format.JsonFormatParser;
import com.example.batch.worker.imports.stage.format.ParseSupport;
import com.example.batch.worker.imports.stage.format.XmlFormatParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的 PARSE 阶段：将原始 payload 按文件格式解析为 NDJSON 暂存文件。
 *
 * <p><b>格式路由</b>：优先取 {@link ImportPayload#fileFormatType()}，其次取模板配置 {@code file_format_type}，
 * 最后按内容推断（{@code {}/{[} → JSON，否则 DELIMITED}）。支持 EXCEL / JSON / XML / FIXED_WIDTH / DELIMITED。
 *
 * <p><b>二进制 payload</b>：若上下文中存在 {@code IMPORT_BINARY_PAYLOAD}（byte[]）， EXCEL
 * 格式直接解析字节，其余格式按模板/payload 指定字符集转为字符串后再路由。
 *
 * <p><b>结果</b>：解析后记录路径写入 {@code PARSED_RECORDS_PATH}，文件状态推进为 {@code PARSED}。 解析记录数为 0
 * 且无跳过记录时返回失败；超过跳过阈值时删除暂存文件并返回失败。
 *
 * <p>暂存文件命名前缀格式：{@code batch-<fileId>-<workerId>-parsed}，存放于 JVM 临时目录。
 */
@Slf4j
@Component
public class ParseStep implements ImportStageStep {

  private static final String KEY_PARSED_COUNT = "parsedCount";
  private static final String FORMAT_EXCEL = "EXCEL";

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ParseSupport support;
  private final Map<String, FormatParser> parsers;
  private final FormatParser defaultParser;

  public ParseStep(
      ObjectMapper objectMapper,
      PlatformFileRuntimeRepository runtimeRepository,
      ImportRecordGovernanceService recordGovernanceService) {
    this.runtimeRepository = runtimeRepository;
    this.support = new ParseSupport(objectMapper, recordGovernanceService);
    ExcelFormatParser excelParser = new ExcelFormatParser(support);
    JsonFormatParser jsonParser = new JsonFormatParser(support);
    XmlFormatParser xmlParser = new XmlFormatParser(support);
    FixedWidthFormatParser fixedWidthParser = new FixedWidthFormatParser(support);
    DelimitedFormatParser delimitedParser = new DelimitedFormatParser(support);
    this.parsers =
        Map.of(
            FORMAT_EXCEL,
            excelParser,
            "JSON",
            jsonParser,
            "XML",
            xmlParser,
            "FIXED_WIDTH",
            fixedWidthParser,
            "DELIMITED",
            delimitedParser);
    this.defaultParser = delimitedParser;
  }

  @Override
  public ImportStage stage() {
    return ImportStage.PARSE;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    Path stagingFile = null;
    Path spoolFile = resolveSpoolPath(context);
    try {
      String payloadText =
          String.valueOf(
              context.getAttributes().getOrDefault("normalizedPayload", context.getRawPayload()));
      ImportPayload importPayload =
          context.getAttributes().get("importPayload") instanceof ImportPayload payload
              ? payload
              : null;
      stagingFile = createStagingFile(context, "parsed");
      long totalCount =
          parsePayloads(
              context,
              payloadText,
              importPayload,
              context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG),
              stagingFile);
      context.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, stagingFile.toString());
      context
          .getAttributes()
          .put(
              KEY_PARSED_COUNT, support.numberValue(context.getAttributes().get(KEY_PARSED_COUNT)));
      context.getAttributes().put("totalCount", totalCount);
      if (totalCount == 0
          && support.numberValue(context.getAttributes().get("skippedCount")) == 0) {
        deleteQuietly(stagingFile);
        return ImportStageResult.failure(stage(), "IMPORT_PARSE_EMPTY", "no records parsed");
      }
      if (!support.withinThreshold(context)) {
        deleteQuietly(stagingFile);
        return ImportStageResult.failure(
            stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
      }
      runtimeRepository.updateFileStatus(
          runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
          "PARSED",
          Map.of(
              KEY_PARSED_COUNT,
              support.numberValue(context.getAttributes().get(KEY_PARSED_COUNT)),
              "totalCount",
              totalCount,
              "skippedCount",
              support.numberValue(context.getAttributes().get("skippedCount")),
              "badRecordCount",
              badRecordCount(context),
              "parsedRecordsPath",
              stagingFile.toString()));
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      if (stagingFile != null) {
        deleteQuietly(stagingFile);
      }
      log.error(
          "parse stage failed: tenantId={}, fileId={}, message={}",
          context == null ? null : context.getTenantId(),
          context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          ex.getMessage(),
          ex);
      return ImportStageResult.failure(stage(), "IMPORT_PARSE_FAILED", ex.getMessage());
    } finally {
      // spool 临时文件生命周期结束于 PARSE：下游 VALIDATE / LOAD 只消费 parsed records staging file。
      if (spoolFile != null) {
        deleteQuietly(spoolFile);
        if (context != null) {
          context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH);
          context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET);
        }
      }
    }
  }

  private long parsePayloads(
      ImportJobContext context,
      String payloadText,
      ImportPayload importPayload,
      Object templateConfig,
      Path stagingFile)
      throws Exception {
    boolean preserveLogicalRow = preserveLogicalRow(context, templateConfig);
    Object binary = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
    try (BufferedWriter writer =
        Files.newBufferedWriter(
            stagingFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      if (binary instanceof byte[] binaryBytes && binaryBytes.length > 0) {
        String format = resolveFormat(importPayload, templateConfig, "");
        if (FORMAT_EXCEL.equalsIgnoreCase(format)) {
          FormatParseRequest request =
              new FormatParseRequest(
                  null, binaryBytes, importPayload, templateConfig, preserveLogicalRow);
          return parsers.get(FORMAT_EXCEL).parse(context, request, writer);
        }
        Charset cs = resolvePayloadTextCharset(importPayload, templateConfig);
        String asText = new String(binaryBytes, cs);
        return dispatchFormat(
            context, asText, importPayload, templateConfig, preserveLogicalRow, writer);
      }
      return dispatchFormat(
          context, payloadText, importPayload, templateConfig, preserveLogicalRow, writer);
    }
  }

  private long dispatchFormat(
      ImportJobContext context,
      String payloadText,
      ImportPayload importPayload,
      Object templateConfig,
      boolean preserveLogicalRow,
      BufferedWriter writer)
      throws Exception {
    Path spoolPath = resolveSpoolPath(context);
    Charset spoolCharset = resolveSpoolCharset(context);
    String format =
        spoolPath != null
            ? resolveFormat(importPayload, templateConfig, "")
            : resolveFormat(importPayload, templateConfig, payloadText);
    FormatParseRequest request =
        new FormatParseRequest(
            payloadText,
            null,
            importPayload,
            templateConfig,
            preserveLogicalRow,
            spoolPath,
            spoolCharset);
    if (FORMAT_EXCEL.equalsIgnoreCase(format)) {
      byte[] bytes =
          payloadText == null ? new byte[0] : payloadText.getBytes(StandardCharsets.UTF_8);
      request =
          new FormatParseRequest(
              payloadText,
              bytes,
              importPayload,
              templateConfig,
              preserveLogicalRow,
              spoolPath,
              spoolCharset);
    }
    FormatParser parser = parsers.getOrDefault(format.toUpperCase(), defaultParser);
    return parser.parse(context, request, writer);
  }

  private static Path resolveSpoolPath(ImportJobContext context) {
    Object v = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH);
    if (v instanceof Path p) {
      return p;
    }
    if (v instanceof String s && !s.isEmpty()) {
      return Path.of(s);
    }
    return null;
  }

  private static Charset resolveSpoolCharset(ImportJobContext context) {
    Object v = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET);
    if (v instanceof Charset cs) {
      return cs;
    }
    if (v instanceof String s && !s.isEmpty()) {
      return EncodingUtils.resolve(s);
    }
    return null;
  }

  private String resolveFormat(
      ImportPayload importPayload, Object templateConfigObject, String payloadText) {
    if (importPayload != null && Texts.hasText(importPayload.fileFormatType())) {
      return importPayload.fileFormatType();
    }
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("file_format_type");
      if (value != null && Texts.hasText(String.valueOf(value))) {
        return String.valueOf(value);
      }
    }
    if (Texts.hasText(payloadText)) {
      String trim = payloadText.trim();
      if (trim.startsWith("{") || trim.startsWith("[")) {
        return "JSON";
      }
    }
    return "DELIMITED";
  }

  private Charset resolvePayloadTextCharset(
      ImportPayload importPayload, Object templateConfigObject) {
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object charset = templateConfig.get("charset");
      if (charset != null && Texts.hasText(String.valueOf(charset))) {
        return EncodingUtils.resolve(String.valueOf(charset));
      }
    }
    if (importPayload != null && Texts.hasText(importPayload.charset())) {
      return EncodingUtils.resolve(importPayload.charset());
    }
    return StandardCharsets.UTF_8;
  }

  private boolean preserveLogicalRow(ImportJobContext context, Object templateConfigObject) {
    if (!(templateConfigObject instanceof Map<?, ?> templateConfig)) {
      return false;
    }
    Object direct = templateConfig.get("load_target_ref");
    if (direct == null) {
      direct = templateConfig.get("loadTargetRef");
    }
    if (direct != null
        && WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED.equalsIgnoreCase(
            String.valueOf(direct).trim())) {
      return true;
    }
    if (templateConfig.get("jdbc_mapped_import") != null
        || templateConfig.get("jdbcMappedImport") != null) {
      return true;
    }
    Map<String, Object> querySchema =
        support.readJsonObject(templateConfig.get("query_param_schema"));
    return querySchema.get("jdbcMappedImport") instanceof Map<?, ?>;
  }

  private long badRecordCount(ImportJobContext context) {
    Object value = context.getAttributes().get("badRecords");
    if (value instanceof List<?> list) {
      return list.size();
    }
    return 0L;
  }

  private void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (Exception ex) {
      log.warn("Failed to delete temp file: {}", path, ex);
    }
  }

  private Path createStagingFile(ImportJobContext context, String phase) throws Exception {
    String fileId = context == null ? "unknown" : String.valueOf(context.getFileId());
    String workerId = context == null ? "worker" : String.valueOf(context.getWorkerId());
    return Files.createTempFile(
        BatchFileConstants.importStagePrefix(fileId, workerId, phase),
        BatchFileConstants.NDJSON_SUFFIX);
  }
}
