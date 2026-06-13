package com.example.batch.worker.imports.stage;

import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.plugin.IdempotencyCapability;
import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.service.DryRunGuard;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.config.WorkerCheckpointProperties;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PipelineStageProgressSink;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
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
 * <p><b>执行路径</b>:流式 — 读取 {@code VALIDATED_RECORDS_PATH} 暂存文件,按 {@code chunk_size} 分块调 {@link
 * ImportLoadPlugin#loadChunk};完成后删除暂存文件(parse/validate 两个阶段的临时文件一并清理)。 失败时故意保留暂存文件,便于运维检查或重放。
 *
 * <p>插件由 {@code load_target_ref}(模板配置)决定,默认为 {@code IMPORT_LOAD_JDBC_MAPPED}。 完成后更新 {@code
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
  // ADR-038 P2:续跑位点(默认禁用,开关 batch.worker.checkpoint.enabled=true 才生效)
  private final WorkerCheckpointProperties checkpointProperties;
  private final ProcessingPositionStore positionStore;

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
    return missingPayload(context);
  }

  private ImportStageResult executeStreaming(ImportJobContext context, Path validatedRecordsPath) {
    if (!Files.exists(validatedRecordsPath)) {
      return missingPayload(context);
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
      boolean partitionReplaceCopy = isPartitionReplaceCopy(plugin, loadCtx);
      boolean partitionStageSwapCopy = isPartitionStageSwapCopy(plugin, loadCtx);
      if (partitionReplaceCopy) {
        requireSinglePartitionForPartitionReplace(context);
        requireCheckpointDisabledForPartitionReplace(plugin);
        ((GenericJdbcMappedImportLoadPlugin) plugin).preparePartitionReplace(loadCtx);
      } else {
        // ADR-038 R3-3:续跑开关开时,plugin 必须自报幂等能力(NONE/UNKNOWN 拒跑)。跨库无 1PC,
        // 崩溃窗口重做最后一个 chunk 的数据安全完全靠 plugin 幂等兜底。
        requireIdempotentPluginIfCheckpointEnabled(plugin);
      }
      int chunkSize = resolveChunkSize(context);

      // ADR-038 P2:续跑位点。开关关闭或 pipeline_instance_id 缺失时退化为今天的行为(从 0 跑、不写位点)。
      CheckpointHandle ckpt = openCheckpoint(context);
      if (ckpt != null && ckpt.completed()) {
        // 该实例的 LOAD 已整体完成,幂等跳过(防止重派后重复 loadChunk)。
        deleteQuietly(validatedRecordsPath);
        deleteQuietly(
            resolvePath(context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH)));
        return markLoaded(context, ckpt.startLineNo());
      }
      long loadedCount =
          loadValidatedRecords(validatedRecordsPath, chunkSize, plugin, loadCtx, ckpt);
      if (partitionStageSwapCopy) {
        ((GenericJdbcMappedImportLoadPlugin) plugin).finishPartitionStageSwap(loadCtx);
      }
      completeCheckpoint(ckpt);
      commit(context, importPayload, loadedCount);
      PipelineStageProgressSink.clear();
      deleteQuietly(validatedRecordsPath);
      deleteQuietly(
          resolvePath(context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH)));
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      // 失败也清掉 progress sink,避免心跳带上失败 stage 的残留(SDK 进程级 sink 跨 stage 共享)
      PipelineStageProgressSink.clear();
      // M-5: 失败时故意不删除暂存文件（validatedRecordsPath / PARSED_RECORDS_PATH），
      // 便于运维检查或重放记录，无需重跑之前的 pipeline 阶段。
      // 加载失败多为模板/数据问题(坏 SQL、缺表、配置非法),message 已表达根因;ERROR 留一行,堆栈降 DEBUG,
      // 避免大量数据级失败刷屏。失败已封装进 ImportStageResult + 死信,不丢信息。
      Object fid = context.getAttributes().get(PipelineRuntimeKeys.FILE_ID);
      log.error(
          "load stage (streaming) failed: tenantId={}, fileId={}, message={}",
          context.getTenantId(),
          fid,
          ex.getMessage());
      log.debug(
          "load stage (streaming) failed stack: tenantId={}, fileId={}",
          context.getTenantId(),
          fid,
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

  private long loadValidatedRecords(
      Path validatedRecordsPath,
      int chunkSize,
      ImportLoadPlugin plugin,
      ImportLoadContext loadCtx,
      CheckpointHandle ckpt)
      throws Exception {
    long skipLines = ckpt == null ? 0L : ckpt.startLineNo();
    long currentLineNo = skipLines;
    long loadedCount = ckpt == null ? 0L : ckpt.startLineNo();
    try (BufferedReader reader =
        Files.newBufferedReader(validatedRecordsPath, StandardCharsets.UTF_8)) {
      // 续跑:跳过上次已处理到的行号(空行也算行,保持与首跑一致的行号语义)
      for (long i = 0; i < skipLines && reader.readLine() != null; i++) {
        // 空循环体:只消费需要跳过的行
      }
      List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
      String line;
      while ((line = reader.readLine()) != null) {
        currentLineNo++;
        if (!Texts.hasText(line)) {
          continue;
        }
        chunk.add(objectMapper.readValue(line, MAP_TYPE));
        if (chunk.size() >= chunkSize) {
          loadedCount = flushAndAdvance(plugin, loadCtx, ckpt, chunk, currentLineNo, loadedCount);
        }
      }
      if (!chunk.isEmpty()) {
        loadedCount = flushAndAdvance(plugin, loadCtx, ckpt, chunk, currentLineNo, loadedCount);
      }
    }
    return loadedCount;
  }

  private long flushAndAdvance(
      ImportLoadPlugin plugin,
      ImportLoadContext loadCtx,
      CheckpointHandle ckpt,
      List<Map<String, Object>> chunk,
      long currentLineNo,
      long loadedCount)
      throws Exception {
    int written = flushChunk(plugin, loadCtx, chunk);
    long updatedLoadedCount = loadedCount + written;
    advanceCheckpoint(ckpt, currentLineNo, written);
    // 流式进度上报(docs/design/pipeline-stage-progress-display.md):totalRowsHint=null
    // 因为预扫整文件估总行数代价大于收益(百万行+一次 O(n) I/O),FE 退化为只显计数器不显 ETA。
    PipelineStageProgressSink.publish(updatedLoadedCount, null);
    chunk.clear();
    return updatedLoadedCount;
  }

  private int flushChunk(
      ImportLoadPlugin plugin, ImportLoadContext loadCtx, List<Map<String, Object>> chunk)
      throws Exception {
    plugin.loadChunk(loadCtx, chunk);
    return chunk.size();
  }

  /**
   * ADR-038 R3-3:续跑开关开时,前置校验 plugin 的幂等能力。{@link IdempotencyCapability#NONE} / {@link
   * IdempotencyCapability#UNKNOWN} 直接 throw {@link WorkerConfigException} 拒跑 —— 跨库无 1PC,崩溃窗口重做
   * chunk 会双写。开关关时不校验(plugin 不会被重做)。
   *
   * <p>详见 {@code docs/runbook/platform-worker-checkpoint-howto.md} §前置校验。
   */
  private void requireIdempotentPluginIfCheckpointEnabled(ImportLoadPlugin plugin) {
    if (checkpointProperties == null || !checkpointProperties.isEnabled()) {
      return;
    }
    IdempotencyCapability cap = plugin.idempotencyCapability();
    if (cap == IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT
        || cap == IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC) {
      return;
    }
    throw new WorkerConfigException(
        "ADR-038 续跑开关开 (batch.worker.checkpoint.enabled=true) 但 plugin "
            + plugin.id()
            + " 未声明幂等能力 (idempotencyCapability="
            + cap
            + ")。跨库无 1PC,崩溃窗口重做 chunk 会双写。"
            + "请让 plugin override idempotencyCapability() 返回 IDEMPOTENT_BY_UNIQUE_CONSTRAINT/"
            + "IDEMPOTENT_BY_PLUGIN_LOGIC,或关闭续跑开关。"
            + "详见 docs/runbook/platform-worker-checkpoint-howto.md §前置校验。");
  }

  private boolean isPartitionReplaceCopy(ImportLoadPlugin plugin, ImportLoadContext loadCtx) {
    return plugin instanceof GenericJdbcMappedImportLoadPlugin jdbcPlugin
        && jdbcPlugin.isPartitionReplaceCopy(loadCtx);
  }

  private boolean isPartitionStageSwapCopy(ImportLoadPlugin plugin, ImportLoadContext loadCtx) {
    return plugin instanceof GenericJdbcMappedImportLoadPlugin jdbcPlugin
        && jdbcPlugin.isPartitionStageSwapCopy(loadCtx);
  }

  private void requireCheckpointDisabledForPartitionReplace(ImportLoadPlugin plugin) {
    if (checkpointProperties == null || !checkpointProperties.isEnabled()) {
      return;
    }
    throw new WorkerConfigException(
        "PARTITION_REPLACE_COPY cannot run with batch.worker.checkpoint.enabled=true for plugin "
            + plugin.id()
            + ": partition replace clears the target partition once before COPY chunks, so"
            + " line-based checkpoint resume would expose partial reload semantics. Disable"
            + " checkpoint for this template or use BATCH_UPSERT.");
  }

  private void requireSinglePartitionForPartitionReplace(ImportJobContext context) {
    long partitionCount =
        numberValue(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT));
    if (partitionCount <= 1L) {
      return;
    }
    throw new WorkerConfigException(
        "PARTITION_REPLACE_COPY cannot run with partitionCount="
            + partitionCount
            + ": each worker partition would clear the same target partition before COPY, which can"
            + " leave partial data. Use shard_strategy=NONE for this template, or split input into"
            + " independent files with distinct logical partitions.");
  }

  // ADR-038 P2 续跑位点辅助 ─────────────────────────────────────────────────────

  /**
   * 启动续跑入口:开关关闭 / pipelineInstanceId 缺失 → 返回 null,走"无位点"原路径(从 0 跑、不写位点)。 开关开 + 有 instance id →
   * 加载已存位点(可能 empty / completed / 中途位点)。
   */
  private CheckpointHandle openCheckpoint(ImportJobContext context) {
    if (checkpointProperties == null || !checkpointProperties.isEnabled()) {
      return null;
    }
    Long pipelineInstanceId =
        runtimeRepository.toLong(
            context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    if (pipelineInstanceId == null || pipelineInstanceId <= 0L) {
      return null;
    }
    String tenantId = context.getTenantId();
    ProcessingPosition pos = positionStore.load(tenantId, pipelineInstanceId, ProcessingStage.LOAD);
    long startLineNo = parseLineNo(pos.positionMarker());
    return new CheckpointHandle(tenantId, pipelineInstanceId, startLineNo, pos.completed());
  }

  /** chunk 落库后推进位点;handle=null 时为关闭态,no-op。 */
  private void advanceCheckpoint(CheckpointHandle handle, long lineNoSeen, int chunkSize) {
    if (handle == null) {
      return;
    }
    positionStore.advance(
        handle.tenantId(),
        handle.pipelineInstanceId(),
        ProcessingStage.LOAD,
        Long.toString(lineNoSeen),
        chunkSize);
  }

  /** 阶段整体完成时调用;handle=null no-op。 */
  private void completeCheckpoint(CheckpointHandle handle) {
    if (handle == null) {
      return;
    }
    positionStore.markCompleted(
        handle.tenantId(), handle.pipelineInstanceId(), ProcessingStage.LOAD);
  }

  private static long parseLineNo(String marker) {
    if (marker == null || marker.isBlank()) {
      return 0L;
    }
    try {
      return Math.max(0L, Long.parseLong(marker.trim()));
    } catch (NumberFormatException ex) {
      // 位点损坏(理论上不应发生)— 退化为 0,从头跑(plugin 幂等兜底)
      return 0L;
    }
  }

  /** LOAD 阶段的续跑句柄;null = 续跑关闭走原路径。 */
  private record CheckpointHandle(
      String tenantId, long pipelineInstanceId, long startLineNo, boolean completed) {}

  /**
   * VALIDATED_RECORDS_PATH 缺失或暂存文件不存在时:全部记录被 skip 则视为成功;否则 fail。 (ADR-038 P3:Legacy
   * customerPayloads 路径已下线,所有写入方必须走 streaming 路径。)
   */
  private ImportStageResult missingPayload(ImportJobContext context) {
    if (context != null && numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT)) > 0) {
      return markLoaded(context, 0L);
    }
    return ImportStageResult.failure(
        stage(),
        "IMPORT_LOAD_NO_PAYLOAD",
        "error.import.load.no_payload",
        new Object[0],
        "no records to load (validated path missing)",
        objectMapper);
  }

  /**
   * P1: 提取 streaming / legacy 共用的 attribute 写入 + file_status 更新(原约 22 行,两侧逐字段对称)。 loadedCount
   * 由调用方传入即可,其他统计仍从 attributes 读最新值。
   */
  private void commit(ImportJobContext context, ImportPayload importPayload, long loadedCount) {
    Map<String, Object> attrs = context.getAttributes();
    attrs.put(KEY_LOADED_COUNT, loadedCount);
    attrs.put(KEY_SUCCESS_COUNT, numberValue(attrs.get(KEY_SUCCESS_COUNT)) + loadedCount);
    ImportStageSupport.updateFileStatusRecoverAware(
        runtimeRepository,
        context,
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
    // 地区(per-run):从触发 payload 的 metadata.region 取(可选);模板 defaultRegion 兜底 + 字典校验在 plugin。
    String region =
        importPayload != null && importPayload.metadata() != null
            ? metaString(importPayload.metadata(), "region")
            : null;
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
        context.getBizDate(),
        bizType,
        region,
        templateCode,
        tc);
  }

  private static String metaString(Map<String, Object> meta, String key) {
    Object v = meta == null ? null : meta.get(key);
    return v == null ? null : String.valueOf(v).trim();
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
    ImportStageSupport.updateFileStatusRecoverAware(
        runtimeRepository,
        context,
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
