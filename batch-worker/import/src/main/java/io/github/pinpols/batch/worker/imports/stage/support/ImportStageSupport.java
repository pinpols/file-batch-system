package io.github.pinpols.batch.worker.imports.stage.support;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * P2: import 三个 Step(Parse / Validate / Load)共享的私有工具方法集中地。
 *
 * <p>原本每个 Step 都有一份 {@code numberValue}/{@code stringValue}/{@code resolveChunkSize}/{@code
 * deleteQuietly}/{@code createStagingFile}/{@code createValidatedFile} 的私有副本(共约 100 行,逻辑完全一致),
 * 任一规则调整时需同步三处。统一到本工具类后,Step 自身只关心 stage 业务。
 *
 * <p>纯工具方法,不持有状态,不入 Spring 容器。
 */
@Slf4j
public final class ImportStageSupport {

  private ImportStageSupport() {}

  /** 把任意 Number / String 数值解析为 long,空白或 null 返回 0。非数值字符串抛 {@link NumberFormatException}。 */
  public static long numberValue(Object value) {
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

  /** 把任意对象转为非空字符串,空白 / "null" / null 统一返回 null。 */
  public static String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
  }

  /**
   * 从 template_config.chunk_size 解析,缺省回退到 {@link ImportWorkerConfiguration#chunkSize()};任何路径都保证 ≥ 1
   * 且不超过 {@link ImportWorkerConfiguration#maxChunkSize()}。
   */
  public static int resolveChunkSize(ImportJobContext context, ImportWorkerConfiguration config) {
    int fallback = config == null ? 500 : config.chunkSize();
    int max = config == null ? 10000 : Math.max(1, config.maxChunkSize());
    int chunkSize = fallback;
    Object templateConfigObject =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("chunk_size");
      if (value instanceof Number number) {
        chunkSize = number.intValue();
      } else if (value != null && Texts.hasText(String.valueOf(value))) {
        chunkSize = Integer.parseInt(String.valueOf(value));
      }
    }
    chunkSize = Math.max(1, chunkSize);
    if (chunkSize > max) {
      throw new IllegalArgumentException(
          "import chunk_size exceeds maxChunkSize: chunkSize=" + chunkSize + ", max=" + max);
    }
    return chunkSize;
  }

  /** 尽力删除暂存文件,失败时 warn,绝不向上抛出(任何调用点都不希望因清理失败影响主流程)。 */
  public static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (Exception ex) {
      log.warn("Failed to delete temp file: {}", path, ex);
    }
  }

  /** ParseStep 用:按 phase(parsed / pre-validated 等)在系统临时目录创建 NDJSON 暂存文件。 */
  public static Path createStagingFile(ImportJobContext context, String phase) throws Exception {
    String fileId = context == null ? "unknown" : String.valueOf(context.getFileId());
    String workerId = context == null ? "worker" : String.valueOf(context.getWorkerId());
    return Files.createTempFile(
        BatchFileConstants.importStagePrefix(fileId, workerId, phase),
        BatchFileConstants.NDJSON_SUFFIX);
  }

  /** ValidateStep 用:VALIDATE 阶段输出的 NDJSON 暂存文件。 */
  public static Path createValidatedFile(ImportJobContext context) throws Exception {
    String fileId = context == null ? "unknown" : String.valueOf(context.getFileId());
    String workerId = context == null ? "worker" : String.valueOf(context.getWorkerId());
    return Files.createTempFile(
        BatchFileConstants.validatedStagePrefix(fileId, workerId),
        BatchFileConstants.NDJSON_SUFFIX);
  }

  /**
   * RECOVER 重放和分片导入都会重新经过 PREPROCESS/PARSE/VALIDATE 来重建当前 task 的本地临时文件，但平台 file_record 是全局记录，可能已被其他
   * task 推进到更后状态。此时不能把状态回退到 PARSING/PARSED/VALIDATED；对"当前状态已达到或超过目标状态"的冲突按幂等处理。NORMAL
   * 非分片模式仍保持严格状态机。
   */
  public static void updateFileStatusRecoverAware(
      PlatformFileRuntimeRepository runtimeRepository,
      ImportJobContext context,
      String targetStatus,
      Map<String, Object> metadata) {
    Long fileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    try {
      runtimeRepository.updateFileStatus(fileId, targetStatus, metadata);
    } catch (BizException exception) {
      if (exception.getCode() != ResultCode.STATE_CONFLICT
          || (!isRecoverMode(context) && !isPartitionedImport(context))
          || !fileStatusAlreadyAtOrAfter(runtimeRepository, fileId, targetStatus)) {
        throw exception;
      }
      log.info(
          "skip file status rollback during import replay: tenantId={}, fileId={},"
              + " targetStatus={}, cause={}",
          context.getTenantId(),
          fileId,
          targetStatus,
          exception.getMessage());
    }
  }

  public static boolean isRecoverMode(ImportJobContext context) {
    if (context == null) {
      return false;
    }
    Object runMode = context.getAttributes().get(PipelineRuntimeKeys.RUN_MODE);
    return runMode != null && "RECOVER".equalsIgnoreCase(String.valueOf(runMode));
  }

  private static boolean isPartitionedImport(ImportJobContext context) {
    if (context == null) {
      return false;
    }
    return numberValue(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT)) > 1L;
  }

  private static boolean fileStatusAlreadyAtOrAfter(
      PlatformFileRuntimeRepository runtimeRepository, Long fileId, String targetStatus) {
    FileStatus current = FileStatus.fromCode(runtimeRepository.currentFileStatus(fileId));
    FileStatus target = FileStatus.fromCode(targetStatus);
    int currentRank = importPipelineRank(current);
    int targetRank = importPipelineRank(target);
    return currentRank > 0 && targetRank > 0 && currentRank >= targetRank;
  }

  private static int importPipelineRank(FileStatus status) {
    if (status == null) {
      return -1;
    }
    return switch (status) {
      case RECEIVED -> 0;
      case PARSING -> 1;
      case PARSED -> 2;
      case VALIDATED -> 3;
      case LOADED -> 4;
      default -> -1;
    };
  }
}
