package com.example.batch.worker.exports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import com.example.batch.worker.exports.stage.format.ExportFormatContext;
import com.example.batch.worker.exports.stage.format.ExportFormatStrategy;
import com.example.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.Texts;

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

  private final ExportDataPluginRegistry exportDataPluginRegistry;
  private final ExportFormatStrategyRegistry formatStrategyRegistry;
  private final ExportWorkerConfiguration workerConfiguration;
  private final ObjectMapper objectMapper;

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
          stage(), "EXPORT_GENERATE_NO_PAYLOAD", "export payload missing");
    }
    Path generatedFile = null;
    try {
      String exportDataRef = resolveExportDataRef(context, exportPayload);
      context.getAttributes().put("exportDataRef", exportDataRef);
      ExportDataContext dataCtx = buildExportDataContext(context, exportPayload);
      ExportDataPlugin dataPlugin = exportDataPluginRegistry.require(exportDataRef);
      Map<String, Object> batch = dataPlugin.loadBatch(dataCtx);
      if (batch.isEmpty()) {
        return ExportStageResult.failure(
            stage(), "EXPORT_BATCH_NOT_FOUND", "export batch not found");
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
            "export row count " + totalCount + " exceeds limit " + maxRows);
      }
      int pageSize = resolvePageSize(context);
      int chunkSize = resolveChunkSize(context);
      String fileFormatType =
          String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
      generatedFile = createGeneratedFile(context, exportPayload, fileFormatType);

      ExportFormatStrategy strategy = formatStrategyRegistry.resolve(fileFormatType);
      long recordCount =
          strategy.generate(
              new ExportFormatContext(
                  batch,
                  batchId,
                  pageSize,
                  chunkSize,
                  generatedFile,
                  context,
                  dataPlugin,
                  dataCtx));

      context.getAttributes().put("exportBatch", batch);
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generatedFile.toString());
      context.getAttributes().put("recordCount", recordCount);
      context
          .getAttributes()
          .put("totalAmount", batch.getOrDefault("total_amount", BigDecimal.ZERO));
      context.getAttributes().put("fileSizeBytes", Files.size(generatedFile));
      return ExportStageResult.success(stage());
    } catch (Exception ex) {
      deleteQuietly(generatedFile);
      return ExportStageResult.failure(stage(), "EXPORT_GENERATE_FAILED", ex.getMessage());
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
    return new ExportDataContext(
        context.getTenantId(),
        context.getJobCode(),
        exportPayload.batchNo(),
        exportPayload.templateCode(),
        tc,
        snapMap);
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
    if (raw instanceof String text && Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, Map.class);
      } catch (Exception ignored) {
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
