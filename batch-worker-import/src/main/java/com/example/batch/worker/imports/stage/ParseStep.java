package com.example.batch.worker.imports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.plugin.WorkerPluginIds;
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
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class ParseStep implements ImportStageStep {

  private static final String KEY_PARSED_COUNT = "parsedCount";

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
            "EXCEL", excelParser,
            "JSON", jsonParser,
            "XML", xmlParser,
            "FIXED_WIDTH", fixedWidthParser,
            "DELIMITED", delimitedParser);
    this.defaultParser = delimitedParser;
  }

  @Override
  public ImportStage stage() {
    return ImportStage.PARSE;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    Path stagingFile = null;
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
          .put(KEY_PARSED_COUNT, support.numberValue(context.getAttributes().get(KEY_PARSED_COUNT)));
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
        if ("EXCEL".equalsIgnoreCase(format)) {
          FormatParseRequest request =
              new FormatParseRequest(
                  null, binaryBytes, importPayload, templateConfig, preserveLogicalRow);
          return parsers.get("EXCEL").parse(context, request, writer);
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
    String format = resolveFormat(importPayload, templateConfig, payloadText);
    FormatParseRequest request =
        new FormatParseRequest(payloadText, null, importPayload, templateConfig, preserveLogicalRow);
    if ("EXCEL".equalsIgnoreCase(format)) {
      byte[] bytes = payloadText.getBytes(StandardCharsets.UTF_8);
      request =
          new FormatParseRequest(
              payloadText, bytes, importPayload, templateConfig, preserveLogicalRow);
    }
    FormatParser parser = parsers.getOrDefault(format.toUpperCase(), defaultParser);
    return parser.parse(context, request, writer);
  }


  private String resolveFormat(
      ImportPayload importPayload, Object templateConfigObject, String payloadText) {
    if (importPayload != null && StringUtils.hasText(importPayload.fileFormatType())) {
      return importPayload.fileFormatType();
    }
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("file_format_type");
      if (value != null && StringUtils.hasText(String.valueOf(value))) {
        return String.valueOf(value);
      }
    }
    if (StringUtils.hasText(payloadText)) {
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
      if (charset != null && StringUtils.hasText(String.valueOf(charset))) {
        return Charset.forName(String.valueOf(charset));
      }
    }
    if (importPayload != null && StringUtils.hasText(importPayload.charset())) {
      return Charset.forName(importPayload.charset());
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
