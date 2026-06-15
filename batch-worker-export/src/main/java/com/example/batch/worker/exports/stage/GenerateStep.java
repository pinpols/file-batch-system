package com.example.batch.worker.exports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.logging.ThrottledLogger;
import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.common.utils.PostgresqlJsonbTexts;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.config.WorkerCheckpointProperties;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PipelineStageProgressSink;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import com.example.batch.worker.exports.stage.format.ExportFormatContext;
import com.example.batch.worker.exports.stage.format.ExportFormatStrategy;
import com.example.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import com.example.batch.worker.exports.stage.format.GenerateCheckpoint;
import com.example.batch.worker.exports.stage.format.GenerateCursorCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 生成导出文件的 Pipeline 阶段。
 *
 * <p>格式选择委托给 {@link ExportFormatStrategyRegistry}：新增导出格式只需添加新的 {@link ExportFormatStrategy}
 * bean，无需修改本类。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateStep implements ExportStageStep {

  // 同一 (tenant, jobCode, batchNo, errorClass) 的重复失败在 60s 内只 WARN 一次。
  // 触发场景:trigger misfire 把同一 job 反复补点 + 配置错(如 biz 表缺失)导致每次都死在同一处,
  // 单条 catch:Exception 的诊断价值在第一次后递减,后续合并成"自上次后又失败 N 次"。
  private static final Duration FAILURE_LOG_COOLDOWN = Duration.ofSeconds(60);

  private final ThrottledLogger failureLogThrottle = new ThrottledLogger(FAILURE_LOG_COOLDOWN);

  private final ExportDataPluginRegistry exportDataPluginRegistry;
  private final ExportFormatStrategyRegistry formatStrategyRegistry;
  private final ExportWorkerConfiguration workerConfiguration;
  private final ObjectMapper objectMapper;
  // ADR-038 P3:GENERATE 续跑(默认禁用,开关 batch.worker.checkpoint.enabled=true 才生效)。
  private final WorkerCheckpointProperties checkpointProperties;
  private final ProcessingPositionStore positionStore;
  private final GenerateCursorCodec cursorCodec;

  @Override
  public ExportStage stage() {
    return ExportStage.GENERATE;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    Object payload = context == null ? null : context.getAttributes().get("exportPayload");
    if (!(payload instanceof ExportPayload exportPayload)
        || !Texts.hasText(exportPayload.batchNo())) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_GENERATE_NO_PAYLOAD",
          "error.export.generate.no_payload",
          new Object[0],
          "export payload missing",
          objectMapper);
    }
    Path generatedFile = null;
    // ADR-038 P3:续跑激活时,失败要保留残文件供下次 truncate+续写(类比 Import 失败保留 staging)。
    boolean keepPartialFileOnFailure = false;
    try {
      Map<String, Object> attrs = context.getAttributes();
      String exportDataRef = resolveExportDataRef(context, exportPayload);
      attrs.put("exportDataRef", exportDataRef);
      ExportDataContext dataCtx = buildExportDataContext(context, exportPayload);
      ExportDataPlugin dataPlugin = exportDataPluginRegistry.require(exportDataRef);
      Map<String, Object> batch = dataPlugin.loadBatch(dataCtx);
      if (batch.isEmpty()) {
        return ExportStageResult.failure(
            stage(),
            "EXPORT_BATCH_NOT_FOUND",
            "error.export.batch_not_found",
            new Object[0],
            "export batch not found",
            objectMapper);
      }
      Object batchId = batch.get("id");
      // H-8: 生成前强制检查最大行数限制，防止 OOM
      long maxRows =
          workerConfiguration != null ? workerConfiguration.effectiveMaxExportRows() : 500_000L;
      Object totalCountObj = batch.get("total_count");
      if (totalCountObj instanceof Number totalCount
          && maxRows > 0
          && totalCount.longValue() > maxRows) {
        return ExportStageResult.failure(
            stage(),
            "EXPORT_EXCEEDS_MAX_ROWS",
            "error.export.exceeds_max_rows",
            new Object[] {totalCount, maxRows},
            "export row count " + totalCount + " exceeds limit " + maxRows,
            objectMapper);
      }
      int pageSize = resolvePageSize(context);
      int chunkSize = resolveChunkSize(context);
      String fileFormatType = String.valueOf(attrs.getOrDefault("exportFileFormatType", "JSON"));

      // ADR-038 P3:续跑开关 + pipelineInstanceId + 非 Excel 才启用续跑。启用时生成文件路径必须确定化
      // (随机 temp 跨崩溃重派会丢残文件);开关关时保持随机 temp,行为与今天完全一致。
      Long checkpointInstanceId = resolveCheckpointInstanceId(context, fileFormatType);
      boolean checkpointEnabled = checkpointInstanceId != null;
      generatedFile =
          checkpointEnabled
              ? deterministicGeneratedFile(checkpointInstanceId, fileFormatType)
              : createGeneratedFile(context, exportPayload, fileFormatType);

      GenerateCheckpoint checkpoint = null;
      if (checkpointEnabled) {
        ProcessingPosition pos =
            positionStore.load(
                context.getTenantId(), checkpointInstanceId, ProcessingStage.GENERATE);
        if (pos.completed() && Files.exists(generatedFile)) {
          // 幂等跳过:GENERATE 已整体完成且文件仍在(STORE 尚未消费)→ 重派不重生成,补齐下游 attribute 即可。
          return completeWithoutRegenerate(context, batch, generatedFile, pos.processedCount());
        }
        checkpoint =
            GenerateCheckpoint.open(
                positionStore,
                cursorCodec,
                context.getTenantId(),
                checkpointInstanceId,
                pos,
                generatedFile);
        keepPartialFileOnFailure = true;
      }

      ExportFormatStrategy strategy = formatStrategyRegistry.resolve(fileFormatType);
      ExportFormatContext formatCtx =
          ExportFormatContext.builder()
              .batch(batch)
              .batchId(batchId)
              .pageSize(pageSize)
              .chunkSize(chunkSize)
              .generatedFile(generatedFile)
              .jobContext(context)
              .dataPlugin(dataPlugin)
              .dataCtx(dataCtx)
              .checkpoint(checkpoint)
              .build();
      long recordCount = strategy.generate(formatCtx);

      if (checkpoint != null) {
        // 整体完成 → 补记终页行数 + 标记 completed;此后该实例重派会走上面的幂等跳过分支。
        checkpoint.complete(recordCount);
      }

      attrs.put("exportBatch", batch);
      attrs.put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generatedFile.toString());
      attrs.put("recordCount", recordCount);
      attrs.put("totalAmount", batch.getOrDefault("total_amount", BigDecimal.ZERO));
      attrs.put("fileSizeBytes", Files.size(generatedFile));
      // 2026-06-04 docs/design/pipeline-stage-progress-display.md:stage 结束清 sink,
      // 避免下一个 CLAIM 心跳带上残留;AbstractExportFormat.generatePaged 已在循环里每 1000 行 publish。
      PipelineStageProgressSink.clear();
      return ExportStageResult.success(stage());
    } catch (Exception ex) {
      // 失败也清,同理
      PipelineStageProgressSink.clear();
      logFailureThrottled(context, exportPayload, ex);

      // 续跑激活时故意保留残文件:下次重派 truncate 到 fsync 位点后续写。否则按今天行为删临时文件。
      if (!keepPartialFileOnFailure) {
        deleteQuietly(generatedFile);
      }
      boolean configError = ex instanceof WorkerConfigException;
      return ExportStageResult.failure(
          stage(),
          configError ? "EXPORT_GENERATE_CONFIG_INVALID" : "EXPORT_GENERATE_FAILED",
          configError ? "error.export.generate.config_invalid" : "error.export.generate.failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          objectMapper);
    }
  }

  private ExportDataContext buildExportDataContext(
      ExportJobContext context, ExportPayload exportPayload) {
    Map<String, Object> tc = templateConfigMap(context);
    Object snap = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
    Map<String, Object> snapMap = new LinkedHashMap<>();
    if (snap instanceof Map<?, ?> raw) {
      raw.forEach((k, v) -> snapMap.put(String.valueOf(k), v));
    }
    int partitionNo =
        intOrDefault(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_NO), 1);
    int partitionCount =
        intOrDefault(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT), 1);
    return new ExportDataContext(
        context.getTenantId(),
        context.getJobCode(),
        exportPayload.batchNo(),
        exportPayload.templateCode(),
        tc,
        snapMap,
        partitionNo,
        partitionCount);
  }

  private static int intOrDefault(Object value, int def) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value == null) {
      return def;
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return def;
    }
  }

  private Map<String, Object> templateConfigMap(ExportJobContext context) {
    Object o = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    return Map.of();
  }

  private String resolveExportDataRef(ExportJobContext context, ExportPayload exportPayload) {
    Map<String, Object> tc = templateConfigMap(context);
    Object v = tc.get("export_data_ref");
    if (v != null && Texts.hasText(String.valueOf(v))) {
      return String.valueOf(v).trim();
    }
    // 与 JdbcMappedImportSpec.extractJdbcMappedImport 的双层 lookup 对齐：顶层没有时再查
    // query_param_schema 里的 export_data_ref。schema 里 file_template_config 没有独立
    // export_data_ref 列，seed 通常把它塞进 query_param_schema jsonb。
    Object qps = tc.get("query_param_schema");
    Map<String, Object> qpsMap = toStringKeyMap(qps);
    Object nested = qpsMap.get("export_data_ref");
    if (nested != null && Texts.hasText(String.valueOf(nested))) {
      return String.valueOf(nested).trim();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toStringKeyMap(Object raw) {
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, val) -> out.put(String.valueOf(k), val));
      return out;
    }
    String text = raw instanceof String s ? s : PostgresqlJsonbTexts.tryExtract(raw);
    if (Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, Map.class);
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(GenerateStep.class, "catch:Exception", ignored);
        return Map.of();
      }
    }
    return Map.of();
  }

  private int resolvePageSize(ExportJobContext context) {
    return resolveTemplateInt(
        context, "page_size", workerConfiguration == null ? 1000 : workerConfiguration.pageSize());
  }

  private int resolveChunkSize(ExportJobContext context) {
    return resolveTemplateInt(
        context, "chunk_size", workerConfiguration == null ? 500 : workerConfiguration.chunkSize());
  }

  private int resolveTemplateInt(ExportJobContext context, String key, int fallback) {
    Object templateConfigObject =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get(key);
      if (value instanceof Number number) {
        return Math.max(1, number.intValue());
      }
      if (value != null && Texts.hasText(String.valueOf(value))) {
        return Math.max(1, Integer.parseInt(String.valueOf(value)));
      }
    }
    return fallback;
  }

  private Path createGeneratedFile(
      ExportJobContext context, ExportPayload payload, String fileFormatType) throws Exception {
    String suffix =
        switch (fileFormatType == null ? "" : fileFormatType.toUpperCase()) {
          case "DELIMITED" -> BatchFileConstants.CSV_SUFFIX;
          case "EXCEL" -> BatchFileConstants.XLSX_SUFFIX;
          case "FIXED_WIDTH" -> BatchFileConstants.TXT_SUFFIX;
          default -> BatchFileConstants.JSON_SUFFIX;
        };
    return Files.createTempFile(
        BatchFileConstants.exportStagePrefix(context.getTenantId(), payload.batchNo()), suffix);
  }

  /**
   * ADR-038 P3:续跑启用时返回 {@code null}(不续跑),否则返回 pipelineInstanceId。续跑要求:开关开 + 有正的 pipelineInstanceId
   * + 非 Excel(SXSSF zip 工作簿无法 append/truncate,不参与续跑,见 runbook)。
   */
  private Long resolveCheckpointInstanceId(ExportJobContext context, String fileFormatType) {
    if (checkpointProperties == null
        || !checkpointProperties.isEnabled()
        || positionStore == null
        || "EXCEL".equalsIgnoreCase(fileFormatType)) {
      return null;
    }
    Long instanceId = toLong(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    return instanceId != null && instanceId > 0L ? instanceId : null;
  }

  /**
   * 续跑用的确定化生成文件路径 {@code ${tmpdir}/file-batch-export/inst-<id>.<ext>}:同一 pipeline 实例多次
   * 重派落到同一文件,崩溃后下次能找到残文件续写。STORE 成功上传后照常删除该文件。
   */
  private Path deterministicGeneratedFile(long pipelineInstanceId, String fileFormatType)
      throws Exception {
    String suffix =
        switch (fileFormatType == null ? "" : fileFormatType.toUpperCase()) {
          case "DELIMITED" -> BatchFileConstants.CSV_SUFFIX;
          case "EXCEL" -> BatchFileConstants.XLSX_SUFFIX;
          case "FIXED_WIDTH" -> BatchFileConstants.TXT_SUFFIX;
          default -> BatchFileConstants.JSON_SUFFIX;
        };
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "file-batch-export");
    Files.createDirectories(dir);
    return dir.resolve("inst-" + pipelineInstanceId + suffix);
  }

  /** 幂等跳过(GENERATE 已完成且文件仍在):不重生成,仅补齐下游 STORE/FEEDBACK 需要的 attribute。 */
  private ExportStageResult completeWithoutRegenerate(
      ExportJobContext context, Map<String, Object> batch, Path generatedFile, long recordCount)
      throws Exception {
    context.getAttributes().put("exportBatch", batch);
    context.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generatedFile.toString());
    context.getAttributes().put("recordCount", recordCount);
    context.getAttributes().put("totalAmount", batch.getOrDefault("total_amount", BigDecimal.ZERO));
    context.getAttributes().put("fileSizeBytes", Files.size(generatedFile));
    PipelineStageProgressSink.clear();
    return ExportStageResult.success(stage());
  }

  private static Long toLong(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private void logFailureThrottled(ExportJobContext context, ExportPayload payload, Exception ex) {
    String tenantId = context == null ? "?" : String.valueOf(context.getTenantId());
    String jobCode = context == null ? "?" : String.valueOf(context.getJobCode());
    String batchNo = payload == null ? "?" : String.valueOf(payload.batchNo());
    String errorClass = ex.getClass().getSimpleName();
    String key = tenantId + '|' + jobCode + '|' + batchNo + '|' + errorClass;
    ThrottledLogger.Decision decision = failureLogThrottle.evaluate(key);
    if (decision.shouldLog()) {
      String msg = ex.getMessage();
      log.warn(
          "export GENERATE failed: tenantId={}, jobCode={}, batchNo={}, errorClass={},"
              + " suppressedSincePrevious={}, message={}",
          tenantId,
          jobCode,
          batchNo,
          errorClass,
          decision.suppressedSincePrevious(),
          msg);
    } else {
      // 抑制窗口内:留一条 DEBUG 兜底,便于 verbose 排查
      SwallowedExceptionLogger.info(GenerateStep.class, "catch:Exception (throttled)", ex);
    }
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
}
