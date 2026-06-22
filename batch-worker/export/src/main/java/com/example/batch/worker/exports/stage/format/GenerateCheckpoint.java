package com.example.batch.worker.exports.stage.format;

import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import com.example.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-038 P3 Export GENERATE 续跑编排:封装「从哪续 / 何时推进位点」,由 {@code GenerateStep} 每个 pipeline 实例创建一个, 注入
 * {@link ExportFormatContext} 供 {@link AbstractExportFormat#generatePaged} 在分页边界调用。
 *
 * <p>位点语义(单文件 + 字节位点截断方案):
 *
 * <ul>
 *   <li>{@link #advance} 仅在「还有后继页」(cursor != null)时调:fsync 后记 (byteOffset, nextCursor, 累计行数)。
 *       终页(cursor == null)<b>不</b>记位点,交由 {@code GenerateStep} 的 {@code markCompleted} 收尾, 从而保证存下来的
 *       cursor 永远是一个有效的续跑起点、绝不为 null(否则续跑会从头重写造成重复)。
 *   <li>续跑时:truncate 文件到 {@link #resumeByteOffset()} 截断崩溃残尾 → 从 {@link #resumeCursor()} 续拉下一页。
 *       崩溃窗口最多重做「最后一个已记位点之后的那一页」,因 truncate 先于重写故无重复。
 *   <li>cursor 类型不被 {@link GenerateCursorCodec} 支持时:{@link #advance} 打一次 warn 后<b>停止记位点</b>
 *       (本次降级为不可续跑的全量跑,生成本身不受影响)。
 * </ul>
 */
public final class GenerateCheckpoint {

  private static final Logger log = LoggerFactory.getLogger(GenerateCheckpoint.class);

  private final ProcessingPositionStore store;
  private final GenerateCursorCodec codec;
  private final String tenantId;
  private final long pipelineInstanceId;

  private final boolean resuming;
  private final long resumeByteOffset;
  private final Object resumeCursor;
  private final long resumeRecordCount;

  private long lastReportedCount;
  private boolean disabled;

  private GenerateCheckpoint(
      ProcessingPositionStore store,
      GenerateCursorCodec codec,
      String tenantId,
      long pipelineInstanceId,
      boolean resuming,
      long resumeByteOffset,
      Object resumeCursor,
      long resumeRecordCount) {
    this.store = store;
    this.codec = codec;
    this.tenantId = tenantId;
    this.pipelineInstanceId = pipelineInstanceId;
    this.resuming = resuming;
    this.resumeByteOffset = resumeByteOffset;
    this.resumeCursor = resumeCursor;
    this.resumeRecordCount = resumeRecordCount;
    this.lastReportedCount = resumeRecordCount;
  }

  /**
   * 依据已存位点 + 生成文件现状决定是否续跑。位点损坏 / 文件缺失 / 偏移越界 / cursor 不可解 → 退化为首跑(resuming=false)。 调用方在 {@code
   * position.completed()} 时不应走到这里(应已幂等跳过)。
   */
  public static GenerateCheckpoint open(
      ProcessingPositionStore store,
      GenerateCursorCodec codec,
      String tenantId,
      long pipelineInstanceId,
      ProcessingPosition position,
      Path generatedFile) {
    boolean resuming = false;
    long offset = 0L;
    Object cursor = null;
    long recordCount = 0L;
    String marker = position == null ? null : position.positionMarker();
    if (marker != null && !position.completed()) {
      try {
        GenerateCursorCodec.Marker m = codec.decodeMarker(marker);
        Object decodedCursor = codec.decodeCursor(m.encodedCursor());
        if (m.byteOffset() > 0
            && decodedCursor != null
            && Files.exists(generatedFile)
            && Files.size(generatedFile) >= m.byteOffset()) {
          resuming = true;
          offset = m.byteOffset();
          cursor = decodedCursor;
          recordCount = position.processedCount();
        }
      } catch (Exception ex) {
        // 位点损坏(理论上不应发生)— 退化为首跑;文件首跑会被 truncate 到 0,不留残尾。
        log.warn(
            "export GENERATE checkpoint marker unusable (instanceId={}): {} — restarting fresh",
            pipelineInstanceId,
            ex.getMessage());
      }
    }
    return new GenerateCheckpoint(
        store, codec, tenantId, pipelineInstanceId, resuming, offset, cursor, recordCount);
  }

  boolean resuming() {
    return resuming;
  }

  long resumeByteOffset() {
    return resumeByteOffset;
  }

  Object resumeCursor() {
    return resumeCursor;
  }

  long resumeRecordCount() {
    return resumeRecordCount;
  }

  /**
   * 分页边界推进位点(由 {@code generatePaged} 在 fsync 后调用,仅当 cursor != null)。cursor 类型不支持时降级停记。
   *
   * @param byteOffset fsync 后的文件字节数(续跑 truncate 目标)
   * @param nextCursor 下一页起始 cursor(非 null)
   * @param recordCountSoFar 截至本页末的累计已写行数
   */
  void advance(long byteOffset, Object nextCursor, long recordCountSoFar) {
    if (disabled) {
      return;
    }
    Optional<String> encoded = codec.encodeCursor(nextCursor);
    if (encoded.isEmpty()) {
      disabled = true;
      log.warn(
          "export GENERATE cursor type {} not serializable — resume disabled for instanceId={} "
              + "(generation continues as a non-resumable full run)",
          nextCursor == null ? "null" : nextCursor.getClass().getName(),
          pipelineInstanceId);
      return;
    }
    long increment = recordCountSoFar - lastReportedCount;
    store.advance(
        tenantId,
        pipelineInstanceId,
        ProcessingStage.GENERATE,
        codec.encodeMarker(byteOffset, encoded.get()),
        increment);
    lastReportedCount = recordCountSoFar;
  }

  /**
   * 阶段整体完成:先把终页那段未记的行数补进位点表(终页 cursor==null 不走 {@link #advance},否则 processedCount 会少最后一页), 再 {@code
   * markCompleted}。补记用 offset=0 的哨兵 marker —— 万一在「补记」与「markCompleted」之间崩溃,{@code open} 见 offset==0
   * 即判定不可续跑、退化为全量重跑(truncate 重写),不会基于哨兵 marker 错误续跑。
   *
   * @param finalRecordCount generate 返回的真实总行数(含续跑前已写)
   */
  public void complete(long finalRecordCount) {
    if (!disabled && finalRecordCount > lastReportedCount) {
      store.advance(
          tenantId,
          pipelineInstanceId,
          ProcessingStage.GENERATE,
          codec.encodeMarker(0L, "S|__completed__"),
          finalRecordCount - lastReportedCount);
      lastReportedCount = finalRecordCount;
    }
    store.markCompleted(tenantId, pipelineInstanceId, ProcessingStage.GENERATE);
  }
}
