package com.example.batch.worker.imports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
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
import com.example.batch.worker.imports.infrastructure.quality.TrailerControlRecord;
import com.example.batch.worker.imports.stage.format.DelimitedFormatParser;
import com.example.batch.worker.imports.stage.format.ExcelFormatParser;
import com.example.batch.worker.imports.stage.format.FixedWidthFormatParser;
import com.example.batch.worker.imports.stage.format.FormatParseRequest;
import com.example.batch.worker.imports.stage.format.FormatParser;
import com.example.batch.worker.imports.stage.format.JsonFormatParser;
import com.example.batch.worker.imports.stage.format.ParseSupport;
import com.example.batch.worker.imports.stage.format.XmlFormatParser;
import com.example.batch.worker.imports.stage.support.ImportStageSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

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
    Map<String, Object> attrs = context.getAttributes();
    try {
      String payloadText =
          String.valueOf(attrs.getOrDefault("normalizedPayload", context.getRawPayload()));
      payloadText = peelTrailerControlRecord(context, payloadText, attrs);
      ImportPayload importPayload =
          attrs.get("importPayload") instanceof ImportPayload payload ? payload : null;
      stagingFile = createStagingFile(context, "parsed");
      long totalCount =
          parsePayloads(
              context,
              payloadText,
              importPayload,
              attrs.get(PipelineRuntimeKeys.TEMPLATE_CONFIG),
              stagingFile);
      // 默认按行 mod 切片:partitionCount > 1 且未关闭时,流式过滤 staging 文件,只保留属于本 partition 的行。
      // (lineNo 0-based;partitionNo 1-based;条件 lineNo % count == partitionNo - 1)
      // totalCount = 文件原始行数(全部 partition 视角);parsedCount = 本 partition 实际处理的行数
      long partitionedCount =
          applyPartitionFilter(
              context, stagingFile, totalCount, attrs.get(PipelineRuntimeKeys.TEMPLATE_CONFIG));
      attrs.put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, stagingFile.toString());
      // 切片时 parsedCount 应反映本 partition 视角(下游 LoadStep 用它做 audit + step output);
      // 不切片时维持原有 support.numberValue(...) 值兜底,保持与历史行为一致。
      Object existingParsedCount = attrs.get(KEY_PARSED_COUNT);
      long parsedCountValue =
          partitionedCount != totalCount
              ? partitionedCount
              : support.numberValue(existingParsedCount);
      attrs.put(KEY_PARSED_COUNT, parsedCountValue);
      attrs.put("totalCount", totalCount);
      if (totalCount == 0 && support.numberValue(attrs.get("skippedCount")) == 0) {
        deleteQuietly(stagingFile);
        return ImportStageResult.failure(
            stage(),
            "IMPORT_PARSE_EMPTY",
            "error.import.parse.empty",
            new Object[0],
            "no records parsed",
            ERROR_OBJECT_MAPPER);
      }
      if (!support.withinThreshold(context)) {
        support.markThresholdExceeded(context);
        deleteQuietly(stagingFile);
        return ImportStageResult.failure(
            stage(),
            "IMPORT_SKIP_THRESHOLD_EXCEEDED",
            "error.import.skip_threshold_exceeded",
            new Object[0],
            "skip threshold exceeded",
            ERROR_OBJECT_MAPPER);
      }
      ImportStageSupport.updateFileStatusRecoverAware(
          runtimeRepository,
          context,
          "PARSED",
          Map.of(
              KEY_PARSED_COUNT,
              support.numberValue(attrs.get(KEY_PARSED_COUNT)),
              "totalCount",
              totalCount,
              "skippedCount",
              support.numberValue(attrs.get("skippedCount")),
              "badRecordCount",
              badRecordCount(context),
              "parsedRecordsPath",
              stagingFile.toString()));
      return ImportStageResult.success(stage());
    } catch (BizException biz) {
      // 业务级 fail-fast(Excel 表头缺列 / sheet 名找不到 / 二进制 .xls 不支持 等):
      // 保留 i18n key + args,转 BUSINESS_ERROR 让 orchestrator retry/dead-letter 政策接管;
      // 不当 infra 异常吞成 error.import.parse.failed 丢失本地化消息。
      if (stagingFile != null) {
        deleteQuietly(stagingFile);
      }
      String tid = context == null ? null : context.getTenantId();
      Object fid =
          context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.FILE_ID);
      log.warn(
          "parse stage business error: tenantId={}, fileId={}, key={}", tid, fid, biz.getMessage());
      return ImportStageResult.failure(stage(), "IMPORT_PARSE_FAILED", biz, ERROR_OBJECT_MAPPER);
    } catch (Exception ex) {
      if (stagingFile != null) {
        deleteQuietly(stagingFile);
      }
      // 解析失败多为输入数据问题(畸形行/格式错),message 已表达根因;ERROR 只留一行,完整堆栈降 DEBUG,
      // 避免大量输入级失败刷屏。失败已封装进 ImportStageResult + 死信,不丢信息。
      String tid = context == null ? null : context.getTenantId();
      Object fid =
          context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.FILE_ID);
      log.error(
          "parse stage failed: tenantId={}, fileId={}, message={}", tid, fid, ex.getMessage());
      log.debug("parse stage failed stack: tenantId={}, fileId={}", tid, fid, ex);
      return ImportStageResult.failure(
          stage(),
          "IMPORT_PARSE_FAILED",
          "error.import.parse.failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          ERROR_OBJECT_MAPPER);
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

  // ADR-041 Phase1.1:opt-in 剥离 trailer 控制记录(末行)。仅 trailer_template.present=true 生效,
  // 只支持 in-memory 文本路径(spool 落盘 / 二进制 payload 跳过并告警)。trailer 是结构性末行(非数据行),
  // present=true 时一律剥离;声明笔数 / 控制总额 stash 进 attributes 交 VALIDATE 的 controlRecordCheck 对账。默认关闭。
  private String peelTrailerControlRecord(
      ImportJobContext context, String payloadText, Map<String, Object> attrs) {
    Map<String, Object> trailerTemplate =
        trailerTemplate(attrs.get(PipelineRuntimeKeys.TEMPLATE_CONFIG));
    if (!TrailerControlRecord.isPresentEnabled(trailerTemplate)) {
      return payloadText;
    }
    if (resolveSpoolPath(context) != null
        || attrs.get(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD) != null) {
      log.warn(
          "trailer peel skipped for spool/binary payload: tenantId={}, fileId={}",
          context.getTenantId(),
          attrs.get(PipelineRuntimeKeys.FILE_ID));
      return payloadText;
    }
    if (!Texts.hasText(payloadText)) {
      return payloadText;
    }
    String body = payloadText;
    while (body.endsWith("\n") || body.endsWith("\r")) {
      body = body.substring(0, body.length() - 1);
    }
    int idx = Math.max(body.lastIndexOf('\n'), body.lastIndexOf('\r'));
    String trailerLine = body.substring(idx + 1);
    TrailerControlRecord trailer = TrailerControlRecord.parse(trailerLine, trailerTemplate);
    if (!isValidTrailer(trailer, trailerTemplate, trailerLine)) {
      throw new IllegalArgumentException(
          "trailer_template.present=true but last line is not a valid trailer control record");
    }
    if (trailer.declaredRecordCount() != null) {
      attrs.put(TrailerControlRecord.ATTR_DECLARED_RECORD_COUNT, trailer.declaredRecordCount());
    }
    if (trailer.declaredControlTotal() != null) {
      attrs.put(TrailerControlRecord.ATTR_DECLARED_CONTROL_TOTAL, trailer.declaredControlTotal());
    }
    return idx < 0 ? "" : body.substring(0, idx);
  }

  private boolean isValidTrailer(
      TrailerControlRecord trailer, Map<String, Object> trailerTemplate, String trailerLine) {
    if (!recordTypeMatches(trailerTemplate, trailerLine)) {
      return false;
    }
    boolean expectsCount = hasConfiguredIndex(trailerTemplate, "recordCountIndex");
    boolean expectsTotal = hasConfiguredIndex(trailerTemplate, "controlTotalIndex");
    if (!expectsCount && !expectsTotal) {
      return trailer.isPresent();
    }
    return (!expectsCount || trailer.declaredRecordCount() != null)
        && (!expectsTotal || trailer.declaredControlTotal() != null);
  }

  private boolean recordTypeMatches(Map<String, Object> template, String trailerLine) {
    Object marker = template == null ? null : template.get("recordType");
    if (marker == null) {
      marker = template == null ? null : template.get("record_type");
    }
    String expected = marker == null ? null : String.valueOf(marker).trim();
    if (!Texts.hasText(expected)) {
      return true;
    }
    String delimiter = strOr(template.get("delimiter"), ",");
    String[] fields = trailerLine.split(Pattern.quote(delimiter), -1);
    int index = intOr(template.get("recordTypeIndex"), 0);
    if (index < 0 || index >= fields.length) {
      return false;
    }
    return expected.equals(fields[index].trim());
  }

  private boolean hasConfiguredIndex(Map<String, Object> template, String key) {
    Object value = template == null ? null : template.get(key);
    if (value == null) {
      return false;
    }
    if (value instanceof Number number) {
      return number.intValue() >= 0;
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim()) >= 0;
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(ParseStep.class, "catch:NumberFormatException", ignored);
      return false;
    }
  }

  private String strOr(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value).trim();
    return Texts.hasText(text) ? text : fallback;
  }

  private int intOr(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(ParseStep.class, "catch:NumberFormatException", ignored);
      return fallback;
    }
  }

  private Map<String, Object> trailerTemplate(Object templateConfig) {
    if (templateConfig instanceof Map<?, ?> tc) {
      Object raw = tc.get("trailer_template");
      if (raw == null) {
        raw = tc.get("trailerTemplate");
      }
      if (raw instanceof Map<?, ?> templateMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        templateMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
      }
    }
    return Map.of();
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
    ImportStageSupport.deleteQuietly(path);
  }

  /**
   * 按 PARTITION_NO / PARTITION_COUNT 流式过滤 staging 文件,只保留本 partition 应处理的行。
   *
   * <p>开关:{@code template_config.partition_aware_parse=false} 关闭(默认开)。partitionCount &le; 1 / 缺
   * PARTITION_NO 等任意条件不满足时直通,与历史行为等价。
   *
   * @return 过滤后实际保留的行数;不需过滤时直接返回 {@code totalCount}
   */
  private long applyPartitionFilter(
      ImportJobContext context, Path stagingFile, long totalCount, Object templateConfig)
      throws Exception {
    Integer partitionNo = intOrNull(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_NO));
    Integer partitionCount =
        intOrNull(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT));
    if (partitionNo == null || partitionCount == null || partitionCount <= 1) {
      return totalCount;
    }
    // range-slice 优化:PREPROCESS 已用对象存储 range GET 只下本片字节(行边界对齐),staging 已只含本片记录。
    // 此时再做 line-mod 过滤会二次切分丢数据,直接跳过(staging 全量即本片)。
    if (Boolean.TRUE.equals(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_PRESLICED))) {
      return totalCount;
    }
    if (templateConfig instanceof Map<?, ?> tc) {
      Object switchVal = tc.get("partition_aware_parse");
      if (switchVal == null) {
        switchVal = tc.get("partitionAwareParse");
      }
      if (switchVal != null && "false".equalsIgnoreCase(String.valueOf(switchVal).trim())) {
        return totalCount;
      }
    }
    if (partitionNo < 1 || partitionNo > partitionCount) {
      log.warn(
          "skip partition filter: partitionNo={} outside [1,{}], tenantId={}, fileId={}",
          partitionNo,
          partitionCount,
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
      return totalCount;
    }
    Path filtered =
        Files.createTempFile(
            BatchFileConstants.importStagePrefix(
                String.valueOf(context.getFileId()),
                String.valueOf(context.getWorkerId()),
                "parsed-p" + partitionNo),
            ".tmp");
    long lineNo = 0;
    long kept = 0;
    int targetMod = partitionNo - 1;
    try (BufferedReader reader = Files.newBufferedReader(stagingFile, StandardCharsets.UTF_8);
        BufferedWriter writer =
            Files.newBufferedWriter(
                filtered,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (lineNo % partitionCount == targetMod) {
          writer.write(line);
          writer.newLine();
          kept++;
        }
        lineNo++;
      }
    }
    Files.move(filtered, stagingFile, StandardCopyOption.REPLACE_EXISTING);
    log.info(
        "partition filter applied: tenantId={}, fileId={}, partition={}/{}, kept={}/{}",
        context.getTenantId(),
        context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
        partitionNo,
        partitionCount,
        kept,
        totalCount);
    return kept;
  }

  private static Integer intOrNull(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(ParseStep.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  private Path createStagingFile(ImportJobContext context, String phase) throws Exception {
    return ImportStageSupport.createStagingFile(context, phase);
  }
}
