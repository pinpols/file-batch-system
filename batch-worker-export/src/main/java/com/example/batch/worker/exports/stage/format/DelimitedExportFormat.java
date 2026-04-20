package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * 生成分隔符平面文件（CSV / TSV / 自定义分隔符），支持可选的引号和转义策略。
 *
 * <p>列布局和格式参数从模板配置中解析；表头行在数据行之前写入。
 */
@Component
public class DelimitedExportFormat extends AbstractExportFormat {

  public DelimitedExportFormat(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String formatType() {
    return "DELIMITED";
  }

  @Override
  public long generate(ExportFormatContext ctx) throws Exception {
    Long batchIdLong = ctx.batchId() == null ? null : Long.valueOf(String.valueOf(ctx.batchId()));
    ExportDataPlugin.DetailPage firstPage =
        ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), null);
    List<ColumnLayout> columns =
        resolveDelimitedColumns(ctx.dataCtx(), ctx.dataPlugin(), ctx.batch(), firstPage.rows());
    if (columns.isEmpty() && firstPage.rows().isEmpty()) {
      return 0L;
    }
    DelimitedFormatConfig formatConfig =
        resolveDelimitedFormatConfig(ctx.dataCtx().templateConfig());

    try (BufferedWriter writer =
        Files.newBufferedWriter(
            ctx.generatedFile(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      writeDelimitedHeaderRows(writer, columns, formatConfig);
      return generatePaged(
          ctx,
          firstPage,
          (batch, detail, rowIndex) -> {
            StringJoiner joiner = new StringJoiner(formatConfig.delimiter());
            for (ColumnLayout column : columns) {
              joiner.add(csv(resolveDelimitedValue(batch, detail, column.source()), formatConfig));
            }
            writer.write(joiner.toString());
            writer.newLine();
            if (ctx.chunkSize() > 0 && (rowIndex + 1) % ctx.chunkSize() == 0) {
              writer.flush();
            }
          });
    }
  }

  private void writeDelimitedHeaderRows(
      BufferedWriter writer, List<ColumnLayout> columns, DelimitedFormatConfig formatConfig)
      throws Exception {
    int headerRows = Math.max(1, formatConfig.headerRows());
    String headerLine =
        buildDelimitedLine(columns.stream().map(ColumnLayout::header).toList(), formatConfig);
    for (int i = 0; i < headerRows; i++) {
      writer.write(headerLine);
      writer.newLine();
    }
  }

  private String buildDelimitedLine(List<String> values, DelimitedFormatConfig formatConfig) {
    StringJoiner joiner = new StringJoiner(formatConfig.delimiter());
    for (String value : values) {
      joiner.add(csv(value, formatConfig));
    }
    return joiner.toString();
  }
}
