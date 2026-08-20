package io.github.pinpols.batch.worker.exports.stage.format;

import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressSink;
import java.util.List;
import java.util.Map;

/**
 * 负责导出格式共用的分页执行边界。
 *
 * <p>格式类只负责把一行数据写成目标格式；cursor 续跑、页边界 fsync、checkpoint 推进和进度节流必须由同一个协作者处理。这样做是为了让
 * CSV、Excel、固定宽度和 JSON 在“文件已写入但 report 丢失”后遵守同一套续跑语义，避免某一种格式漏记位点或重复推进。
 */
final class ExportPageGenerationCoordinator {

  private static final int DEFAULT_MAX_PAGES = 100_000;
  private static final int PROGRESS_PUBLISH_EVERY_N_ROWS = 1000;

  private ExportPageGenerationCoordinator() {}

  static long generatePaged(
      ExportFormatContext ctx,
      ExportDataPlugin.DetailPage preFetchedFirstPage,
      FileSync fileSync,
      PageRowWriter rowWriter)
      throws Exception {
    Long batchIdLong =
        EmptyChecks.isNull(ctx.batchId()) ? null : Long.valueOf(String.valueOf(ctx.batchId()));
    GenerateCheckpoint checkpoint = ctx.checkpoint();
    boolean resuming = EmptyChecks.isNotNull(checkpoint) && checkpoint.resuming();
    long recordCount = resuming ? checkpoint.resumeRecordCount() : 0L;
    ExportDataPlugin.DetailPage page;
    if (resuming) {
      page = ctx.dataPlugin()
          .loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), checkpoint.resumeCursor());
    } else {
      page = EmptyChecks.isNotNull(preFetchedFirstPage)
          ? preFetchedFirstPage
          : ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), null);
    }
    int pageNo = 0;
    while (true) {
      if (EmptyChecks.isNull(page)) {
        break;
      }
      List<Map<String, Object>> details = page.rows();
      if (EmptyChecks.isEmpty(details)) {
        break;
      }
      for (Map<String, Object> detail : details) {
        rowWriter.writeRow(ctx.batch(), detail, recordCount);
        recordCount++;
        if (recordCount % PROGRESS_PUBLISH_EVERY_N_ROWS == 0) {
          PipelineStageProgressSink.publish(recordCount, null);
        }
      }
      Object cursor = page.nextCursor();
      // 终页不记 null cursor；下一次续跑必须从最后一个有效页边界开始，避免重复写整份文件。
      if (EmptyChecks.isNotNull(checkpoint)
          && EmptyChecks.isNotNull(fileSync)
          && EmptyChecks.isNotNull(cursor)) {
        long byteOffset = fileSync.flushAndSync();
        checkpoint.advance(byteOffset, cursor, recordCount);
      }
      if (EmptyChecks.isNull(cursor)) {
        break;
      }
      if (++pageNo >= DEFAULT_MAX_PAGES) {
        throw new IllegalStateException("export page iteration exceeded MAX_PAGES="
            + DEFAULT_MAX_PAGES
            + "; data plugin likely returning stale cursor");
      }
      page = ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), cursor);
    }
    // 最后一页可能不足节流阈值，终态仍需上报准确计数。
    PipelineStageProgressSink.publish(recordCount, null);
    return recordCount;
  }

  @FunctionalInterface
  interface FileSync {
    long flushAndSync() throws Exception;
  }

  @FunctionalInterface
  interface PageRowWriter {
    void writeRow(Map<String, Object> batch, Map<String, Object> detail, long rowIndex)
        throws Exception;
  }
}
