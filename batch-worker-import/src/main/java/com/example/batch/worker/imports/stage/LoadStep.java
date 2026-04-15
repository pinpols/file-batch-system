package com.example.batch.worker.imports.stage;

import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.plugin.ImportLoadPluginRegistry;
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
import org.springframework.util.StringUtils;

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
    String validatedRecordsPath =
        stringValue(context.getAttributes().get(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH));
    if (StringUtils.hasText(validatedRecordsPath)) {
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
          if (!StringUtils.hasText(line)) {
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
      context.getAttributes().put(KEY_LOADED_COUNT, loadedCount);
      context
          .getAttributes()
          .put(
              KEY_SUCCESS_COUNT,
              numberValue(context.getAttributes().get(KEY_SUCCESS_COUNT)) + loadedCount);
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
              Boolean.TRUE.equals(context.getAttributes().get(KEY_MANUAL_REVIEW_REQUIRED)),
              "loadTargetRef",
              resolveLoadTargetRef(context, importPayload)));
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
      return ImportStageResult.failure(stage(), "IMPORT_LOAD_FAILED", ex.getMessage());
    }
  }

  private long flushChunk(
      ImportLoadPlugin plugin, ImportLoadContext loadCtx, List<Map<String, Object>> chunk)
      throws Exception {
    plugin.loadChunk(loadCtx, chunk);
    return chunk.size();
  }

  private ImportStageResult executeLegacy(ImportJobContext context) {
    Object payload = context == null ? null : context.getAttributes().get("customerPayloads");
    if (!(payload instanceof List<?> payloads) || payloads.isEmpty()) {
      if (numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT)) > 0) {
        return markLoaded(context, 0L);
      }
      return ImportStageResult.failure(
          stage(), "IMPORT_LOAD_NO_PAYLOAD", "no records to load (legacy path)");
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
      context.getAttributes().put(KEY_LOADED_COUNT, n);
      context
          .getAttributes()
          .put(KEY_SUCCESS_COUNT, numberValue(context.getAttributes().get(KEY_SUCCESS_COUNT)) + n);
      runtimeRepository.updateFileStatus(
          runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
          "LOADED",
          Map.of(
              KEY_LOADED_COUNT,
              n,
              KEY_SUCCESS_COUNT,
              numberValue(context.getAttributes().get(KEY_SUCCESS_COUNT)),
              KEY_SKIPPED_COUNT,
              numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT)),
              "badRecordCount",
              badRecordCount(context),
              KEY_MANUAL_REVIEW_REQUIRED,
              Boolean.TRUE.equals(context.getAttributes().get(KEY_MANUAL_REVIEW_REQUIRED)),
              "loadTargetRef",
              resolveLoadTargetRef(context, importPayload)));
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      log.error(
          "load stage (legacy) failed: tenantId={}, fileId={}, message={}",
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          ex.getMessage(),
          ex);
      return ImportStageResult.failure(stage(), "IMPORT_LOAD_FAILED", ex.getMessage());
    }
  }

  private ImportLoadContext buildLoadContext(
      ImportJobContext context, ImportPayload importPayload, String sourceFileName) {
    Map<String, Object> tc = templateConfigMap(context);
    String batchNo =
        importPayload == null || !StringUtils.hasText(importPayload.batchNo())
            ? context.getBizDate()
            : importPayload.batchNo();
    String bizType =
        importPayload != null && StringUtils.hasText(importPayload.bizType())
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
    if (v != null && StringUtils.hasText(String.valueOf(v))) {
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
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    String text = String.valueOf(value);
    if (text.isBlank()) {
      return 0L;
    }
    return Long.parseLong(text);
  }

  private int resolveChunkSize(ImportJobContext context) {
    int fallback = workerConfiguration == null ? 500 : workerConfiguration.chunkSize();
    Object templateConfigObject =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("chunk_size");
      if (value instanceof Number number) {
        return Math.max(1, number.intValue());
      }
      if (value != null && StringUtils.hasText(String.valueOf(value))) {
        return Math.max(1, Integer.parseInt(String.valueOf(value)));
      }
    }
    return Math.max(1, fallback);
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return StringUtils.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
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

  private Path resolvePath(Object value) {
    String text = stringValue(value);
    if (!StringUtils.hasText(text)) {
      return null;
    }
    return Path.of(text);
  }
}
