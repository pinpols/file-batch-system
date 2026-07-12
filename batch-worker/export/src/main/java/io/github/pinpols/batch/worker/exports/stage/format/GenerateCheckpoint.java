package io.github.pinpols.batch.worker.exports.stage.format;

import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPosition;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingStage;
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
   * 完成 marker 的 cursor 哨兵:类型标签 {@code C} <b>不是</b> {@link GenerateCursorCodec} 的合法解码标签(合法为
   * L/I/BI/D/B/TS/LDT/DT/S)。故完成 marker 的 byteOffset 位可安全复用为「最终文件字节数」指纹:一旦「补记指纹」与「{@code
   * markCompleted}」之间崩溃,{@code open} 尝试 {@code decodeCursor} 会因未知标签 {@code C} 抛错 → 退化为全量重跑(truncate
   * 重写),绝不会基于完成哨兵错误续跑。
   */
  static final String COMPLETED_CURSOR_SENTINEL = "C|__completed__";

  /**
   * 阶段整体完成:补记终页未记的行数(终页 cursor==null 不走 {@link #advance},否则 processedCount 会少最后一页),并把**最终文件字节数**
   * 写进完成 marker(offset 位),再 {@code markCompleted}。
   *
   * <p>P1-2 文件指纹:{@code GenerateStep} 幂等跳过前用该字节数与残文件 {@code Files.size()} 比对——双故障(GENERATE 完→STORE
   * 前崩→重派 fresh 半写→再崩→completed+残文件)下字节数不符即拒绝跳过、退化全量重写,杜绝上传半截文件。
   *
   * @param finalRecordCount generate 返回的真实总行数(含续跑前已写)
   * @param finalFileSizeBytes 生成文件最终字节数(完整性指纹)
   */
  public void complete(long finalRecordCount, long finalFileSizeBytes) {
    long increment = 0L;
    if (!disabled && finalRecordCount > lastReportedCount) {
      increment = finalRecordCount - lastReportedCount;
      lastReportedCount = finalRecordCount;
    }
    // 始终写含字节数指纹的完成 marker(即便无新增行数,也要落 fileSize 指纹供跳过前校验)。
    store.advance(
        tenantId,
        pipelineInstanceId,
        ProcessingStage.GENERATE,
        codec.encodeMarker(finalFileSizeBytes, COMPLETED_CURSOR_SENTINEL),
        increment);
    store.markCompleted(tenantId, pipelineInstanceId, ProcessingStage.GENERATE);
  }

  /**
   * 解析完成 marker 里的文件字节数指纹;非完成哨兵格式 / 解析失败 → 返回 {@code -1}(调用方据此拒绝幂等跳过、退化全量重写)。 兼容旧哨兵({@code
   * 0@S|__completed__},无真实字节数)—— 其 offset=0 与真实文件字节数不符,自然触发重写(安全)。
   */
  public static long parseCompletedFileSize(GenerateCursorCodec codec, String marker) {
    if (marker == null) {
      return -1L;
    }
    try {
      GenerateCursorCodec.Marker parsed = codec.decodeMarker(marker);
      if (!COMPLETED_CURSOR_SENTINEL.equals(parsed.encodedCursor())) {
        return -1L;
      }
      return parsed.byteOffset();
    } catch (RuntimeException ex) {
      return -1L;
    }
  }
}
