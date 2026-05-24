package com.example.batch.worker.imports.stage;

import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.service.DryRunGuard;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import com.example.batch.worker.imports.stage.support.ImportStageSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的 LOAD 阶段：将校验通过的记录通过 {@link ImportLoadPlugin} 批量写入目标存储。
 *
 * <p><b>两条执行路径</b>：
 *
 * <ul>
 *   <li><b>流式路径</b>：读取 {@code VALIDATED_RECORDS_PATH} 暂存文件，按 {@code chunk_size} 分块调 {@link
 *       ImportLoadPlugin#loadChunk}；完成后删除暂存文件（parse/validate 两个阶段的临时文件一并清理）。 失败时故意保留暂存文件，便于运维检查或重放。
 *   <li><b>Legacy 路径</b>：暂存文件不存在时，从上下文 {@code customerPayloads} 列表直接加载（向后兼容旧调用方）。
 * </ul>
 *
 * <p>插件由 {@code load_target_ref}（模板配置）决定，默认为 {@code IMPORT_LOAD_JDBC_MAPPED}。 完成后更新 {@code
 * file_record} 状态为 {@code LOADED} 并写入加载统计元数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadStep implements ImportStageStep {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_FILE_NAME = "file_name";
  private static final String KEY_SUCCESS_COUNT = "successCount";
  private static final String KEY_SKIPPED_COUNT = "skippedCount";
  private static final String KEY_LOADED_COUNT = "loadedCount";
  private static final String KEY_MANUAL_REVIEW_REQUIRED = "manualReviewRequired";

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ImportLoadPluginRegistry importLoadPluginRegistry;
  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ImportWorkerConfiguration workerConfiguration;
  private final ObjectMapper objectMapper;

  @Override
  public ImportStage stage() {
    return ImportStage.LOAD;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    // ADR-026: 演练模式不写目标存储；以 validated path 行数为预估让 PROCESS / FEEDBACK 拿到完整 attribute
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      long expected = estimateDryRunLoadedCount(context);
      return markLoaded(context, expected);
    }
    String validatedRecordsPath =
        stringValue(context.getAttributes().get(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH));
    if (Texts.hasText(validatedRecordsPath)) {
      return executeStreaming(context, Path.of(validatedRecordsPath));
    }
    return executeLegacy(context);
  }

  private ImportStageResult executeStreaming(ImportJobContext context, Path validatedRecordsPath) {
    if (!Files.exists(validatedRecordsPath)) {
      return executeLegacy(context);
    }
    try {
      if (numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT)) > 0
          && Files.size(validatedRecordsPath) == 0L) {
        return markLoaded(context, 0L);
      }
      ImportPayload importPayload =
          context.getAttributes().get("importPayload") instanceof ImportPayload item ? item : null;
      Object fileRecord = context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD);
      String sourceFileName =
          fileRecord instanceof Map<?, ?> row && row.get(KEY_FILE_NAME) != null
              ? String.valueOf(row.get(KEY_FILE_NAME))
              : context.getFileId();
      ImportLoadPlugin plugin =
          importLoadPluginRegistry.require(resolveLoadTargetRef(context, importPayload));
      ImportLoadContext loadCtx = buildLoadContext(context, importPayload, sourceFileName);
      int chunkSize = resolveChunkSize(context);
      long loadedCount = 0L;
      try (BufferedReader reader =
          Files.newBufferedReader(validatedRecordsPath, StandardCharsets.UTF_8)) {
        List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
        String line;
        while ((line = reader.readLine()) != null) {
          if (!Texts.hasText(line)) {
            continue;
          }
          chunk.add(objectMapper.readValue(line, MAP_TYPE));
          if (chunk.size() >= chunkSize) {
            loadedCount += flushChunk(plugin, loadCtx, chunk);
            chunk.clear();
          }
        }
        if (!chunk.isEmpty()) {
          loadedCount += flushChunk(plugin, loadCtx, chunk);
        }
      }
      commit(context, importPayload, loadedCount);
      deleteQuietly(validatedRecordsPath);
      deleteQuietly(
          resolvePath(context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH)));
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      // M-5: 失败时故意不删除暂存文件（validatedRecordsPath / PARSED_RECORDS_PATH），
      // 便于运维检查或重放记录，无需重跑之前的 pipeline 阶段。
      log.error(
          "load stage (streaming) failed: tenantId={}, fileId={}, message={}",
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          ex.getMessage(),
          ex);
      boolean configError = ex instanceof WorkerConfigException;
      return ImportStageResult.failure(
          stage(),
          configError ? "IMPORT_LOAD_CONFIG_INVALID" : "IMPORT_LOAD_FAILED",
          configError ? "error.import.load.config_invalid" : "error.import.load.failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          objectMapper);
    }
  }

  private long flushChunk(
      ImportLoadPlugin plugin, ImportLoadContext loadCtx, List<Map<String, Object>> chunk)
      throws Exception {
    plugin.loadChunk(loadCtx, chunk);
    return chunk.size();
  }

  /**
   * Legacy 路径:从 context 的 customerPayloads 列表加载,无暂存文件。新写入方一律走 streaming 路径
   * (VALIDATED_RECORDS_PATH);此方法仅作旧调用方兼容,将在下一版本下线,不再加新特性。
   */
  @Deprecated
  private ImportStageResult executeLegacy(ImportJobContext context) {
    Object payload = context == null ? null : context.getAttributes().get("customerPayloads");
    if (!(payload instanceof List<?> payloads) || payloads.isEmpty()) {
      if (numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT)) > 0) {
        return markLoaded(context, 0L);
      }
      return ImportStageResult.failure(
          stage(),
          "IMPORT_LOAD_NO_PAYLOAD",
          "error.import.load.no_payload",
          new Object[0],
          "no records to load (legacy path)",
          objectMapper);
    }
    @SuppressWarnings("unchecked")
    List<CustomerImportPayload> customerPayloads = (List<CustomerImportPayload>) payloads;
    ImportPayload importPayload =
        context.getAttributes().get("importPayload") instanceof ImportPayload item ? item : null;
    Object fileRecord = context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD);
    String sourceFileName =
        fileRecord instanceof Map<?, ?> row && row.get(KEY_FILE_NAME) != null
            ? String.valueOf(row.get(KEY_FILE_NAME))
            : context.getFileId();
    try {
      ImportLoadPlugin plugin =
          importLoadPluginRegistry.require(resolveLoadTargetRef(context, importPayload));
      ImportLoadContext loadCtx = buildLoadContext(context, importPayload, sourceFileName);
      int chunkSize = resolveChunkSize(context);
      long n = 0L;
      List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
      for (CustomerImportPayload customerPayload : customerPayloads) {
        chunk.add(objectMapper.convertValue(customerPayload, MAP_TYPE));
        if (chunk.size() >= chunkSize) {
          n += flushChunk(plugin, loadCtx, chunk);
          chunk.clear();
        }
      }
      if (!chunk.isEmpty()) {
        n += flushChunk(plugin, loadCtx, chunk);
      }
      commit(context, importPayload, n);
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      log.error(
          "load stage (legacy) failed: tenantId={}, fileId={}, message={}",
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          ex.getMessage(),
          ex);
      boolean configError = ex instanceof WorkerConfigException;
      return ImportStageResult.failure(
          stage(),
          configError ? "IMPORT_LOAD_CONFIG_INVALID" : "IMPORT_LOAD_FAILED",
          configError ? "error.import.load.config_invalid" : "error.import.load.failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          objectMapper);
    }
  }

  /**
   * P1: 提取 streaming / legacy 共用的 attribute 写入 + file_status 更新(原约 22 行,两侧逐字段对称)。 loadedCount
   * 由调用方传入即可,其他统计仍从 attributes 读最新值。
   */
  private void commit(ImportJobContext context, ImportPayload importPayload, long loadedCount) {
    Map<String, Object> attrs = context.getAttributes();
    attrs.put(KEY_LOADED_COUNT, loadedCount);
    attrs.put(KEY_SUCCESS_COUNT, numberValue(attrs.get(KEY_SUCCESS_COUNT)) + loadedCount);
    runtimeRepository.updateFileStatus(
        runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID)),
        "LOADED",
        Map.of(
            KEY_LOADED_COUNT,
            loadedCount,
            KEY_SUCCESS_COUNT,
            numberValue(attrs.get(KEY_SUCCESS_COUNT)),
            KEY_SKIPPED_COUNT,
            numberValue(attrs.get(KEY_SKIPPED_COUNT)),
            "badRecordCount",
            badRecordCount(context),
            KEY_MANUAL_REVIEW_REQUIRED,
            Boolean.TRUE.equals(attrs.get(KEY_MANUAL_REVIEW_REQUIRED)),
            "loadTargetRef",
            resolveLoadTargetRef(context, importPayload)));
  }

  private ImportLoadContext buildLoadContext(
      ImportJobContext context, ImportPayload importPayload, String sourceFileName) {
    Map<String, Object> tc = templateConfigMap(context);
    String batchNo =
        importPayload == null || !Texts.hasText(importPayload.batchNo())
            ? context.getBizDate()
            : importPayload.batchNo();
    String bizType =
        importPayload != null && Texts.hasText(importPayload.bizType())
            ? importPayload.bizType()
            : context.getJobCode();
    String templateCode = importPayload != null ? importPayload.templateCode() : null;
    return new ImportLoadContext(
        context.getTenantId(),
        context.getJobCode(),
        String.valueOf(
            context
                .getAttributes()
                .getOrDefault(PipelineRuntimeKeys.TRACE_ID, context.getWorkerId())),
        context.getWorkerId(),
        sourceFileName,
        batchNo,
        bizType,
        templateCode,
        tc);
  }

  private String resolveLoadTargetRef(ImportJobContext context, ImportPayload importPayload) {
    Map<String, Object> tc = templateConfigMap(context);
    Object v = tc.get("load_target_ref");
    if (v != null && Texts.hasText(String.valueOf(v))) {
      return String.valueOf(v).trim();
    }
    return WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
  }

  private Map<String, Object> templateConfigMap(ImportJobContext context) {
    Object o = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    return Map.of();
  }

  private ImportStageResult markLoaded(ImportJobContext context, long loadedCount) {
    runtimeRepository.updateFileStatus(
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
        "LOADED",
        Map.of(
            KEY_LOADED_COUNT,
            loadedCount,
            KEY_SUCCESS_COUNT,
            numberValue(context.getAttributes().get(KEY_SUCCESS_COUNT)),
            KEY_SKIPPED_COUNT,
            numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT)),
            "badRecordCount",
            badRecordCount(context),
            KEY_MANUAL_REVIEW_REQUIRED,
            Boolean.TRUE.equals(context.getAttributes().get(KEY_MANUAL_REVIEW_REQUIRED))));
    context.getAttributes().put(KEY_LOADED_COUNT, loadedCount);
    return ImportStageResult.success(stage());
  }

  private long badRecordCount(ImportJobContext context) {
    Object value = context.getAttributes().get("badRecords");
    if (value instanceof List<?> list) {
      return list.size();
    }
    return 0L;
  }

  private long numberValue(Object value) {
    return ImportStageSupport.numberValue(value);
  }

  private int resolveChunkSize(ImportJobContext context) {
    return ImportStageSupport.resolveChunkSize(context, workerConfiguration);
  }

  private String stringValue(Object value) {
    return ImportStageSupport.stringValue(value);
  }

  private void deleteQuietly(Path path) {
    ImportStageSupport.deleteQuietly(path);
  }

  private Path resolvePath(Object value) {
    String text = stringValue(value);
    if (!Texts.hasText(text)) {
      return null;
    }
    return Path.of(text);
  }

  /**
   * ADR-026 dry-run：预估"如果实盘会落多少行"以填 successCount/loadedCount。 数 validated NDJSON 行数；缺失时 fallback 到
   * 0。
   */
  private long estimateDryRunLoadedCount(ImportJobContext context) {
    if (context == null) {
      return 0L;
    }
    String validatedPath =
        stringValue(context.getAttributes().get(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH));
    if (!Texts.hasText(validatedPath)) {
      return 0L;
    }
    Path path = Path.of(validatedPath);
    if (!Files.exists(path)) {
      return 0L;
    }
    try (var stream = Files.lines(path, StandardCharsets.UTF_8)) {
      return stream.filter(Texts::hasText).count();
    } catch (Exception ignored) {
      return 0L;
    }
  }
}
