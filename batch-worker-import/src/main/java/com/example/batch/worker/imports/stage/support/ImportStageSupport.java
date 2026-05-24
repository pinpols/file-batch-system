package com.example.batch.worker.imports.stage.support;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.ImportJobContext;
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
   * 从 template_config.chunk_size 解析,缺省回退到 {@link ImportWorkerConfiguration#chunkSize()};任何路径都保证 ≥
   * 1。
   */
  public static int resolveChunkSize(ImportJobContext context, ImportWorkerConfiguration config) {
    int fallback = config == null ? 500 : config.chunkSize();
    Object templateConfigObject =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get("chunk_size");
      if (value instanceof Number number) {
        return Math.max(1, number.intValue());
      }
      if (value != null && Texts.hasText(String.valueOf(value))) {
        return Math.max(1, Integer.parseInt(String.valueOf(value)));
      }
    }
    return Math.max(1, fallback);
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
}
