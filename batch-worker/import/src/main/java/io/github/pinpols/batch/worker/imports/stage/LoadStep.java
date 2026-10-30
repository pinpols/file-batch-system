package io.github.pinpols.batch.worker.imports.stage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.CheckpointPartitionGuard;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
import io.github.pinpols.batch.worker.imports.plugin.ImportLoadPluginRegistry;
import io.github.pinpols.batch.worker.imports.stage.support.ImportStageSupport;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class LoadStep implements ImportStageStep {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_FILE_NAME = "file_name";
  private static final String KEY_SKIPPED_COUNT = PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT;
  private static final String KEY_LOADED_COUNT = PipelineRuntimeKeys.IMPORT_LOADED_COUNT;

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ImportLoadPluginRegistry importLoadPluginRegistry;
  private final PlatformFileRecordRepository fileRecords;
  private final ImportWorkerConfiguration workerConfiguration;
  private final ObjectMapper objectMapper;
  // ADR-038 P2:续跑位点(默认启用,开关 batch.worker.checkpoint.enabled=false 可关闭)
  private final WorkerCheckpointProperties checkpointProperties;
  private final ProcessingPositionStore positionStore;
  private final PipelineStageProgressRegistry progressRegistry;
  private final TaskExecutionClient taskExecutionClient;

  @Autowired
  public LoadStep(
      ImportLoadPluginRegistry importLoadPluginRegistry,
      PlatformFileRecordRepository fileRecords,
      ImportWorkerConfiguration workerConfiguration,
      ObjectMapper objectMapper,
      WorkerCheckpointProperties checkpointProperties,
      ProcessingPositionStore positionStore,
      PipelineStageProgressRegistry progressRegistry,
      TaskExecutionClient taskExecutionClient) {
    this.importLoadPluginRegistry = importLoadPluginRegistry;
    this.fileRecords = fileRecords;
    this.workerConfiguration = workerConfiguration;
    this.objectMapper = objectMapper;
    this.checkpointProperties = checkpointProperties;
    this.positionStore = positionStore;
    this.progressRegistry = progressRegistry;
    this.taskExecutionClient = taskExecutionClient;
  }

  @Override
  public ImportStage stage() {
    return ImportStage.LOAD;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    if (context == null) {
      return ImportStageResult.failure(
          stage(), "IMPORT_LOAD_CONTEXT_MISSING", "context is required");
    }
    // ADR-026: 演练模式不写目标存储；以 validated path 行数为预估让 PROCESS / FEEDBACK 拿到完整 attribute
    if (DryRunGuard.fromAttributes(context.getAttributes()).isDryRun()) {
      long expected = estimateDryRunLoadedCount(context);
      return markLoaded(context, expected);
    }
    String validatedRecordsPath =
        stringValue(context.getAttributes().get(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH));
    if (Texts.hasText(validatedRecordsPath)) {
      Path validatedRecordsFile = Path.of(validatedRecordsPath);
      return executeStreaming(context, validatedRecordsFile);
    }
    return missingPayload();
  }

  private ImportStageResult executeStreaming(ImportJobContext context, Path validatedRecordsPath) {
    if (!Files.exists(validatedRecordsPath)) {
      return missingPayload();
    }
    Map<String, Object> attrs = context.getAttributes();
    try {
      boolean validatedFileIsEmpty = Files.size(validatedRecordsPath) == 0L;
      if (validatedFileIsEmpty
          && numberValue(attrs.get(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT)) > 0) {
        return missingPayload();
      }
      boolean hasSkippedRowsAndNoValidatedPayload =
          numberValue(attrs.get(KEY_SKIPPED_COUNT)) > 0 && validatedFileIsEmpty;
      if (hasSkippedRowsAndNoValidatedPayload) {
        return markLoaded(context, 0L);
      }
      ImportPayload importPayload =
          attrs.get(PipelineRuntimeKeys.IMPORT_PAYLOAD) instanceof ImportPayload item ? item : null;
      Object fileRecord = attrs.get(PipelineRuntimeKeys.FILE_RECORD);
      String sourceFileName = fileRecord instanceof Map<?, ?> row && row.get(KEY_FILE_NAME) != null
          ? String.valueOf(row.get(KEY_FILE_NAME))
          : context.getFileId();
      String loadTargetRef = resolveLoadTargetRef(context);
      ImportLoadPlugin plugin = importLoadPluginRegistry.require(loadTargetRef);
      IdempotencyCapability idempotencyCapability = plugin.idempotencyCapability();
      ImportLoadContext loadCtx = buildLoadContext(context, importPayload, sourceFileName);
      boolean partitionReplaceCopy = isPartitionReplaceCopy(plugin, loadCtx);
      boolean partitionStageSwapCopy = isPartitionStageSwapCopy(plugin, loadCtx);
      boolean checkpointDegraded = checkpointDegradedByMultiPartition(context);
      LoadPluginExecutionFence executionFence = new LoadPluginExecutionFence(
          taskExecutionClient, context, idempotencyCapability, partitionReplaceCopy);
      if (partitionReplaceCopy) {
        requireSinglePartitionForPartitionReplace(context);
        requireCheckpointDisabledForPartitionReplace(plugin);
        if (!executionFence.authorizeBeforeSideEffect()) {
          throw new NonIdempotentExecutionFenceException();
        }
        ((GenericJdbcMappedImportLoadPlugin) plugin).preparePartitionReplace(loadCtx);
      } else if (!checkpointDegraded) {
        // ADR-038 R3-3:续跑开关开时,plugin 必须自报幂等能力(NONE/UNKNOWN 拒跑)。跨库无 1PC,
        // 崩溃窗口重做最后一个 chunk 的数据安全完全靠 plugin 幂等回退。
        // 多分区降级时仍在首次写入前持久化非幂等围栏,避免失败后被 Orchestrator 自动重放。
        requireIdempotentPluginIfCheckpointEnabled(plugin, idempotencyCapability);
      }
      int chunkSize = resolveChunkSize(context);

      // ADR-038 P2:续跑位点。开关关闭或 pipeline_instance_id 缺失时退化为今天的行为(从 0 跑、不写位点)。
      CheckpointHandle ckpt = openCheckpoint(context, validatedRecordsPath);
      if (ckpt != null && ckpt.completed()) {
        return recoverCompletedCheckpoint(context, validatedRecordsPath, ckpt);
      }
      LoadExecution loadExecution =
          new LoadExecution(context, plugin, loadCtx, ckpt, executionFence);
      long loadedCount = loadValidatedRecords(validatedRecordsPath, chunkSize, loadExecution);
      requireValidatedRecordCount(attrs, loadedCount);
      if (partitionStageSwapCopy) {
        ((GenericJdbcMappedImportLoadPlugin) plugin).finishPartitionStageSwap(loadCtx);
      }
      if (EmptyChecks.isNotNull(ckpt) && loadedCount == 0L) {
        advanceCheckpoint(ckpt, 0L, 0);
      }
      completeCheckpoint(ckpt);
      commit(context, loadedCount);
      progressRegistry.clear(context, stage().name());
      deleteQuietly(validatedRecordsPath);
      Object parsedRecordsPathValue = attrs.get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
      Path parsedRecordsPath = resolvePath(parsedRecordsPathValue);
      deleteQuietly(parsedRecordsPath);
      return ImportStageResult.success(stage());
    } catch (Exception ex) {
      return streamingFailure(context, attrs, ex);
    }
  }

  private ImportStageResult recoverCompletedCheckpoint(
      ImportJobContext context, Path validatedRecordsPath, CheckpointHandle checkpoint) {
    Map<String, Object> attrs = context.getAttributes();
    requireValidatedRecordCount(attrs, checkpoint.processedCount());
    // 同一上下文状态写入失败后重试时，不重复计入已加载的记录。
    if (!attrs.containsKey(KEY_LOADED_COUNT)) {
      long previousSuccessCount = numberValue(attrs.get(ImportRuntimeKeys.SUCCESS_COUNT));
      attrs.put(
          ImportRuntimeKeys.SUCCESS_COUNT, previousSuccessCount + checkpoint.processedCount());
      attrs.put(KEY_LOADED_COUNT, checkpoint.processedCount());
    }
    ImportStageResult recovered = markLoaded(context, checkpoint.processedCount());
    deleteQuietly(validatedRecordsPath);
    Object parsedRecordsPathValue = attrs.get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
    Path parsedRecordsPath = resolvePath(parsedRecordsPathValue);
    deleteQuietly(parsedRecordsPath);
    return recovered;
  }

  /** 失败只清心跳进度，保留暂存文件和既有位点，随后按原错误契约归类与记录诊断。 */
  private ImportStageResult streamingFailure(
      ImportJobContext context, Map<String, Object> attrs, Exception ex) {
    // 失败也清掉当前 task/stage 的进度，避免下一次心跳继续展示已失败任务。
    progressRegistry.clear(context, stage().name());
    // M-5: 失败时故意不删除暂存文件（validatedRecordsPath / PARSED_RECORDS_PATH），
    // 便于运维检查或重放记录，无需重跑之前的 pipeline 阶段。
    // 加载失败多为模板/数据问题(坏 SQL、缺表、配置非法),message 已表达根因;ERROR 留一行,堆栈降 DEBUG,
    // 避免大量数据级失败产生日志噪音。失败已封装进 ImportStageResult + 死信,不丢信息。
    Object fid = attrs.get(PipelineRuntimeKeys.FILE_ID);
    log.error(
        "load stage (streaming) failed: tenantId={}, fileId={}, message={}",
        context.getTenantId(),
        fid,
        SwallowedExceptionLogger.summary(ex));
    log.debug(
        "load stage (streaming) failed stack: tenantId={}, fileId={}",
        context.getTenantId(),
        fid,
        ex);
    boolean configError = ex instanceof WorkerConfigException;
    if (ex instanceof NonIdempotentExecutionFenceException) {
      return ImportStageResult.failure(
          stage(), TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN, ex.getMessage());
    }
    ImportStage failureStage = stage();
    String failureCode = configError ? "IMPORT_LOAD_CONFIG_INVALID" : "IMPORT_LOAD_FAILED";
    String failureMessageKey =
        configError ? "error.import.load.config_invalid" : "error.import.load.failed";
    String localizedMessageArgument = ex.getMessage();
    String fallbackMessage = ex.getMessage();
    return ImportStageResult.failure(
        failureStage,
        failureCode,
        failureMessageKey,
        new Object[] {localizedMessageArgument},
        fallbackMessage,
        objectMapper);
  }

  private long loadValidatedRecords(
      Path validatedRecordsPath, int chunkSize, LoadExecution loadExecution) throws Exception {
    CheckpointHandle checkpoint = loadExecution.checkpoint();
    long skipLines = EmptyChecks.isNull(checkpoint) ? 0L : checkpoint.startLineNo();
    long currentLineNo = skipLines;
    // loaded_count 初值用已写库记录数,不能用 startLineNo(行号含空行 → 续跑后 loaded_count 虚增)。
    long loadedCount = EmptyChecks.isNull(checkpoint) ? 0L : checkpoint.processedCount();
    try (BufferedReader reader =
        Files.newBufferedReader(validatedRecordsPath, StandardCharsets.UTF_8)) {
      // 续跑:跳过上次已处理到的行号(空行也算行,保持与首跑一致的行号语义)
      for (long i = 0; i < skipLines; i++) {
        String skippedLine = reader.readLine();
        if (skippedLine == null) {
          break;
        }
      }
      List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
      String line;
      while ((line = reader.readLine()) != null) {
        currentLineNo++;
        if (!Texts.hasText(line)) {
          continue;
        }
        Map<String, Object> parsedRecord = objectMapper.readValue(line, MAP_TYPE);
        chunk.add(parsedRecord);
        if (chunk.size() >= chunkSize) {
          loadedCount = flushAndAdvance(loadExecution, chunk, currentLineNo, loadedCount);
        }
      }
      if (!chunk.isEmpty()) {
        loadedCount = flushAndAdvance(loadExecution, chunk, currentLineNo, loadedCount);
      }
    }
    return loadedCount;
  }

  private long flushAndAdvance(
      LoadExecution loadExecution,
      List<Map<String, Object>> chunk,
      long currentLineNo,
      long loadedCount)
      throws Exception {
    Object validatedCount =
        loadExecution.context().getAttributes().get(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT);
    if (EmptyChecks.isNotNull(validatedCount)) {
      long expected = numberValue(validatedCount);
      if (loadedCount > expected || chunk.size() > expected - loadedCount) {
        throw new IllegalStateException("Import LOAD chunk exceeds validated record count");
      }
    }
    int written = flushChunk(loadExecution, chunk);
    long updatedLoadedCount = loadedCount + written;
    advanceCheckpoint(loadExecution.checkpoint(), currentLineNo, written);
    // 流式进度上报(docs/design/pipeline-stage-progress-display.md):totalRowsHint=null
    // 因为预扫整文件估总行数代价大于收益(百万行+一次 O(n) I/O),FE 退化为只显计数器不显 ETA。
    progressRegistry.publish(loadExecution.context(), stage().name(), updatedLoadedCount, null);
    chunk.clear();
    return updatedLoadedCount;
  }

  private void requireValidatedRecordCount(Map<String, Object> attributes, long loadedCount) {
    Object validatedCount = attributes.get(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT);
    if (EmptyChecks.isNotNull(validatedCount) && numberValue(validatedCount) != loadedCount) {
      throw new IllegalStateException(
          "Import LOAD record count does not match validation: expected="
              + numberValue(validatedCount)
              + ", loaded="
              + loadedCount);
    }
  }

  private int flushChunk(LoadExecution loadExecution, List<Map<String, Object>> chunk)
      throws Exception {
    if (!loadExecution.executionFence().authorizeBeforeSideEffect()) {
      throw new NonIdempotentExecutionFenceException();
    }
    int handled = loadExecution.plugin().loadChunk(loadExecution.loadContext(), chunk);
    if (handled != chunk.size()) {
      throw new IllegalStateException(
          "Import LOAD plugin did not confirm the entire chunk: expected="
              + chunk.size()
              + ", confirmed="
              + handled);
    }
    return handled;
  }

  /**
   * ADR-038 R3-3:单分区续跑启用时,前置校验 plugin 的幂等能力。{@link IdempotencyCapability#NONE} / {@link
   * IdempotencyCapability#UNKNOWN} 直接 throw {@link WorkerConfigException} 拒跑 —— 跨库无 1PC,崩溃窗口重做
   * chunk 会双写。续跑关闭或因多分区降级时,非幂等 plugin 必须在首个外部副作用前持久化执行围栏;失败后按
   * {@code EXTERNAL_OUTCOME_UNKNOWN} 处理,避免自动重放。
   *
   * <p>详见 {@code docs/runbook/platform-worker-checkpoint-howto.md} §前置校验。
   *
   * <p>能力声明按 plugin 粒度，但 generic JDBC mapped plugin 还会在实际写入前按模板校验：{@code BATCH_UPSERT}
   * 必须声明 {@code conflictColumns}，分区替换策略不允许进入 checkpoint 路径。自定义 plugin 只有在所有受支持模板和
   * 执行策略均可安全重放时，才能声明幂等能力。
   */
  private void requireIdempotentPluginIfCheckpointEnabled(
      ImportLoadPlugin plugin, IdempotencyCapability capability) {
    if (checkpointProperties == null || !checkpointProperties.isEnabled()) {
      return;
    }
    if (isReplaySafe(capability)) {
      return;
    }
    throw new WorkerConfigException(
        "ADR-038 checkpoint is enabled (batch.worker.checkpoint.enabled=true), but plugin "
            + plugin.id()
            + " does not declare an idempotency capability (idempotencyCapability="
            + capability
            + "). Without a cross-database 1PC, replaying a chunk after a crash can write duplicates. "
            + "Override idempotencyCapability() in the plugin to return IDEMPOTENT_BY_UNIQUE_CONSTRAINT/"
            + "IDEMPOTENT_BY_PLUGIN_LOGIC, or disable checkpointing and rely on the persisted execution fence. "
            + "See docs/runbook/platform-worker-checkpoint-howto.md for the prerequisite validation.");
  }

  private static boolean isReplaySafe(IdempotencyCapability capability) {
    return capability == IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT
        || capability == IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC;
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
    throw new WorkerConfigException("PARTITION_REPLACE_COPY cannot run with partitionCount="
        + partitionCount
        + ": each worker partition would clear the same target partition before COPY, which can"
        + " leave partial data. Use shard_strategy=NONE for this template, or split input into"
        + " independent files with distinct logical partitions.");
  }

  // ADR-038 P2 续跑位点辅助 ─────────────────────────────────────────────────────

  /**
   * 启动续跑入口:开关关闭 / pipelineInstanceId 缺失 / 多分区任务 → 返回 null,走"无位点"原路径(从 0 跑、不写位点)。 开关开 + 有 instance
   * id → 加载已存位点(可能 empty / completed / 中途位点)。
   */
  private CheckpointHandle openCheckpoint(ImportJobContext context, Path validatedRecordsPath)
      throws Exception {
    if (checkpointProperties == null || !checkpointProperties.isEnabled()) {
      return null;
    }
    if (checkpointDegradedByMultiPartition(context)) {
      return null;
    }
    Long pipelineInstanceId = PlatformRuntimeValues.toLong(
        context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    if (pipelineInstanceId == null || pipelineInstanceId <= 0L) {
      return null;
    }
    String tenantId = context.getTenantId();
    ProcessingPosition pos = positionStore.load(tenantId, pipelineInstanceId, ProcessingStage.LOAD);
    MessageDigest digest = MessageDigest.getInstance(CryptoAlgorithms.SHA_256);
    try (InputStream input =
        new DigestInputStream(Files.newInputStream(validatedRecordsPath), digest)) {
      input.transferTo(OutputStream.nullOutputStream());
    }
    String fileDigest = HexFormat.of().formatHex(digest.digest());
    long startLineNo = parseLineNo(pos, fileDigest);
    return new CheckpointHandle(
        tenantId,
        pipelineInstanceId,
        startLineNo,
        pos.processedCount(),
        pos.completed(),
        fileDigest);
  }

  /**
   * ADR-038 P0 多分区降级守卫:partitionCount > 1(mod 切片分区 / ADR-046 bundle 展开)时,K 个 partition 任务共享同一
   * {@code pipeline_instance}(UPSERT 幂等键是 {@code related_job_instance_id},见
   * PlatformFileRuntimeMapper.insertPipelineInstance),位点行 {@code (tenant, pipelineInstanceId,
   * LOAD)} 会被各 partition 交叉读写:A 的行号 marker 会让 B 续跑时跳错行、A 的 markCompleted 会让 B 整段误跳过 —— 是数据正确性风险。
   *
   * <p>P0 保守处理:多分区整体降级为不续跑(行为同开关关)。多分区场景的崩溃恢复本就由 {@code job_partition} 分区级重跑覆盖(二者正交,见设计文档
   * §1.8);分区内行级续跑要按 partition 拆位点键,属 P1+ 新逻辑,不在 P0 做。
   */
  private boolean checkpointDegradedByMultiPartition(ImportJobContext context) {
    Object rawPartitionCount = context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT);
    // P2 fail-closed:缺失=单分区放行;present 但非法(非数字/<=0)=拓扑不可判定→降级(见 CheckpointPartitionGuard)。
    if (!CheckpointPartitionGuard.shouldDegrade(rawPartitionCount)) {
      return false;
    }
    log.debug(
        "checkpoint resume degraded (multi-partition or illegal partition count, task shares"
            + " pipeline_instance): tenantId={}, fileId={}, partitionCount={}",
        context.getTenantId(),
        context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
        rawPartitionCount);
    return true;
  }

  /** chunk 写入数据库后推进位点;handle=null 时为关闭态,no-op。 */
  private void advanceCheckpoint(CheckpointHandle handle, long lineNoSeen, int chunkSize) {
    if (handle == null) {
      return;
    }
    positionStore.advance(
        handle.tenantId(),
        handle.pipelineInstanceId(),
        ProcessingStage.LOAD,
        lineNoSeen + ":" + handle.fileDigest(),
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

  private static long parseLineNo(ProcessingPosition position, String fileDigest) {
    String marker = position.positionMarker();
    if (EmptyChecks.isNull(marker) && position.processedCount() == 0L && !position.completed()) {
      return 0L;
    }
    if (EmptyChecks.isNull(marker)) {
      throw new IllegalStateException("Import LOAD checkpoint has no file identity");
    }
    int separator = marker.indexOf(':');
    if (separator <= 0 || !fileDigest.equals(marker.substring(separator + 1))) {
      throw new IllegalStateException("Import LOAD checkpoint file identity mismatch");
    }
    try {
      long lineNumber = Long.parseLong(marker.substring(0, separator));
      if (lineNumber < 0L) {
        throw new IllegalStateException("Import LOAD checkpoint line is invalid");
      }
      return lineNumber;
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Import LOAD checkpoint line is invalid", ex);
    }
  }

  /** LOAD 阶段的续跑句柄;null = 续跑关闭走原路径。 */
  // startLineNo = 物理行号(含空行,用于续跑跳行);processedCount = 已确认写库的记录数累计
  // (用于 loaded_count 审计,绝不能用行号代替——文件含空行时行号 > 记录数会虚增统计)。
  private record CheckpointHandle(
      String tenantId,
      long pipelineInstanceId,
      long startLineNo,
      long processedCount,
      boolean completed,
      String fileDigest) {}

  private record LoadExecution(
      ImportJobContext context,
      ImportLoadPlugin plugin,
      ImportLoadContext loadContext,
      CheckpointHandle checkpoint,
      LoadPluginExecutionFence executionFence) {}

  /** 重放安全性未知的 Import 插件写入前，先持久化重试围栏。 */
  private static final class LoadPluginExecutionFence {

    private final TaskExecutionClient taskExecutionClient;
    private final String tenantId;
    private final Long taskId;
    private final String workerId;
    private final String partitionInvocationId;
    private final boolean replaySafe;
    private boolean confirmed;

    private LoadPluginExecutionFence(
        TaskExecutionClient taskExecutionClient,
        ImportJobContext context,
        IdempotencyCapability capability,
        boolean forceBeforeDestructiveReplacement) {
      this.taskExecutionClient = taskExecutionClient;
      this.tenantId = context.getTenantId();
      this.taskId = parseTaskId(context.getAttributes().get(PipelineRuntimeKeys.TASK_ID));
      this.workerId = context.getWorkerId();
      Object invocationValue =
          context.getAttributes().get(PipelineRuntimeKeys.PARTITION_INVOCATION_ID);
      this.partitionInvocationId = invocationValue instanceof String value ? value : null;
      // 插件整体能力不足以证明破坏性的分区删除或交换在部分写入后
      // 仍可安全重放；这些策略必须使用持久化围栏。
      this.replaySafe = !forceBeforeDestructiveReplacement && isReplaySafe(capability);
    }

    private boolean authorizeBeforeSideEffect() {
      if (replaySafe || confirmed) {
        return true;
      }
      boolean hasPersistedExecutionIdentity = EmptyChecks.isNotNull(taskId)
          && Texts.hasText(tenantId)
          && Texts.hasText(workerId)
          && Texts.hasText(partitionInvocationId);
      if (!hasPersistedExecutionIdentity || EmptyChecks.isNull(taskExecutionClient)) {
        return false;
      }
      try {
        confirmed = taskExecutionClient.markNonIdempotentExecutionStarted(
            tenantId, taskId, workerId, partitionInvocationId);
      } catch (RuntimeException exception) {
        SwallowedExceptionLogger.warn(
            LoadStep.class, "catch:nonIdempotentPluginExecutionFence", exception);
      }
      return confirmed;
    }

    private static Long parseTaskId(Object value) {
      if (EmptyChecks.isNull(value)) {
        return null;
      }
      try {
        return PlatformRuntimeValues.toLong(value);
      } catch (NumberFormatException exception) {
        return null;
      }
    }
  }

  private static final class NonIdempotentExecutionFenceException extends Exception {

    private static final long serialVersionUID = 1L;

    private NonIdempotentExecutionFenceException() {
      super("non-idempotent Import plugin execution fence was not confirmed");
    }
  }

  /** 缺少校验暂存文件时失败；跳过计数本身不能证明没有待加载记录。 */
  private ImportStageResult missingPayload() {
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
  private void commit(ImportJobContext context, long loadedCount) {
    Map<String, Object> attrs = context.getAttributes();
    attrs.put(KEY_LOADED_COUNT, loadedCount);
    long previousSuccessCount = numberValue(attrs.get(ImportRuntimeKeys.SUCCESS_COUNT));
    long updatedSuccessCount = previousSuccessCount + loadedCount;
    attrs.put(ImportRuntimeKeys.SUCCESS_COUNT, updatedSuccessCount);
    String loadedStatus = FileStatus.LOADED.code();
    long successCount = numberValue(attrs.get(ImportRuntimeKeys.SUCCESS_COUNT));
    long skippedCount = numberValue(attrs.get(KEY_SKIPPED_COUNT));
    long badRecordCount = badRecordCount(context);
    boolean manualReviewRequired =
        Boolean.TRUE.equals(attrs.get(ImportRuntimeKeys.MANUAL_REVIEW_REQUIRED));
    String loadTargetRef = resolveLoadTargetRef(context);
    Map<String, Object> fileStatusMetadata = Map.of(
        KEY_LOADED_COUNT,
        loadedCount,
        ImportRuntimeKeys.SUCCESS_COUNT,
        successCount,
        KEY_SKIPPED_COUNT,
        skippedCount,
        "badRecordCount",
        badRecordCount,
        ImportRuntimeKeys.MANUAL_REVIEW_REQUIRED,
        manualReviewRequired,
        "loadTargetRef",
        loadTargetRef);
    ImportStageSupport.updateFileStatusRecoverAware(
        fileRecords, context, loadedStatus, fileStatusMetadata);
  }

  private ImportLoadContext buildLoadContext(
      ImportJobContext context, ImportPayload importPayload, String sourceFileName) {
    Map<String, Object> tc = templateConfigMap(context);
    String batchNo = importPayload == null || !Texts.hasText(importPayload.batchNo())
        ? context.getBizDate()
        : importPayload.batchNo();
    String bizType = importPayload != null && Texts.hasText(importPayload.bizType())
        ? importPayload.bizType()
        : context.getJobCode();
    String templateCode = importPayload != null ? importPayload.templateCode() : null;
    // 地区(per-run):从触发 payload 的 metadata.region 取(可选);模板 defaultRegion 回退 + 字典校验在 plugin。
    String region = importPayload != null && importPayload.metadata() != null
        ? metaString(importPayload.metadata(), "region")
        : null;
    String tenantId = context.getTenantId();
    String jobCode = context.getJobCode();
    Object traceIdValue =
        context.getAttributes().getOrDefault(PipelineRuntimeKeys.TRACE_ID, context.getWorkerId());
    String traceId = String.valueOf(traceIdValue);
    String workerId = context.getWorkerId();
    return new ImportLoadContext(
        tenantId,
        jobCode,
        traceId,
        workerId,
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
    if (EmptyChecks.isNull(v)) {
      return null;
    }
    String rawText = String.valueOf(v);
    return rawText.trim();
  }

  ReplayCapabilityDeclaration replayCapability(Map<String, Object> frozenTemplateConfig) {
    String loadTargetRef = resolveLoadTargetRef(frozenTemplateConfig);
    return importLoadPluginRegistry.replayCapability(loadTargetRef, frozenTemplateConfig);
  }

  private String resolveLoadTargetRef(ImportJobContext context) {
    Map<String, Object> templateConfig = templateConfigMap(context);
    return resolveLoadTargetRef(templateConfig);
  }

  private String resolveLoadTargetRef(Map<String, Object> templateConfig) {
    Map<String, Object> resolvedConfig =
        EmptyChecks.isNull(templateConfig) ? Map.of() : templateConfig;
    Object v = resolvedConfig.get("load_target_ref");
    String presenceText = EmptyChecks.isNull(v) ? null : String.valueOf(v);
    if (EmptyChecks.isNotNull(v) && Texts.hasText(presenceText)) {
      String rawLoadTarget = String.valueOf(v);
      return rawLoadTarget.trim();
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
    String loadedStatus = FileStatus.LOADED.code();
    long successCount = numberValue(context.getAttributes().get(ImportRuntimeKeys.SUCCESS_COUNT));
    long skippedCount = numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT));
    long badRecordCount = badRecordCount(context);
    boolean manualReviewRequired =
        Boolean.TRUE.equals(context.getAttributes().get(ImportRuntimeKeys.MANUAL_REVIEW_REQUIRED));
    Map<String, Object> fileStatusMetadata = Map.of(
        KEY_LOADED_COUNT,
        loadedCount,
        ImportRuntimeKeys.SUCCESS_COUNT,
        successCount,
        KEY_SKIPPED_COUNT,
        skippedCount,
        "badRecordCount",
        badRecordCount,
        ImportRuntimeKeys.MANUAL_REVIEW_REQUIRED,
        manualReviewRequired);
    ImportStageSupport.updateFileStatusRecoverAware(
        fileRecords, context, loadedStatus, fileStatusMetadata);
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
    try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
      return stream.filter(Texts::hasText).count();
    } catch (Exception ignored) {
      return 0L;
    }
  }
}
