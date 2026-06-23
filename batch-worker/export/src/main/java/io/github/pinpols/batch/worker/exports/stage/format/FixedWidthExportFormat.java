package io.github.pinpols.batch.worker.exports.stage.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import java.io.BufferedWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 生成固定宽度平面文件。
 *
 * <p>每个字段按配置的 {@code width} 填充或截断；可选的 {@code record_length} 配置 通过在整行右侧补空格来强制统一行长。
 */
@Component
public class FixedWidthExportFormat extends AbstractExportFormat {

  public FixedWidthExportFormat(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String formatType() {
    return "FIXED_WIDTH";
  }

  @Override
  public long generate(ExportFormatContext ctx) throws Exception {
    Long batchIdLong = ctx.batchId() == null ? null : Long.valueOf(String.valueOf(ctx.batchId()));
    ExportDataPlugin.DetailPage firstPage =
        ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), null);
    List<ColumnLayout> columns =
        resolveFixedWidthColumns(ctx.dataCtx(), ctx.dataPlugin(), ctx.batch(), firstPage.rows());
    if (columns.isEmpty() && firstPage.rows().isEmpty()) {
      return 0L;
    }
    int recordLength = resolveTemplateInt(ctx.jobContext(), "record_length", 0);
    int headerRows = resolveDelimitedFormatConfig(ctx.dataCtx().templateConfig()).headerRows();

    // ADR-038 P3:首页仅用于列解析(只读、幂等);续跑时 generatePaged 会忽略它、从 resumeCursor 续拉。
    try (ResumableExportFile file = openExportFile(ctx)) {
      BufferedWriter writer = file.writer();
      // 续跑时残文件已含表头,不可重写。
      if (!isResuming(ctx)) {
        writeFixedWidthHeaderRows(writer, columns, recordLength, headerRows);
      }
      return generatePaged(
          ctx,
          firstPage,
          file::flushAndSync,
          (batch, detail, rowIndex) -> {
            StringBuilder line = new StringBuilder();
            for (ColumnLayout column : columns) {
              line.append(
                  fixedWidth(resolveDelimitedValue(batch, detail, column.source()), column));
            }
            if (recordLength > 0) {
              line = new StringBuilder(padRight(line.toString(), recordLength));
            }
            writer.write(line.toString());
            writer.newLine();
            if (ctx.chunkSize() > 0 && (rowIndex + 1) % ctx.chunkSize() == 0) {
              writer.flush();
            }
          });
    }
  }

  private void writeFixedWidthHeaderRows(
      BufferedWriter writer, List<ColumnLayout> columns, int recordLength, int headerRows)
      throws Exception {
    int effectiveHeaderRows = Math.max(1, headerRows);
    String header = fixedWidthLine(columns, recordLength, ColumnLayout::header);
    for (int i = 0; i < effectiveHeaderRows; i++) {
      writer.write(header);
      writer.newLine();
    }
  }
}
