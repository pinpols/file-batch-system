package io.github.pinpols.batch.worker.exports.stage.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

    // ADR-041 Phase1.4:出站内嵌 trailer 控制记录(默认关闭,trailer_template.present=true 才启用)。
    Map<String, Object> trailerTemplate = toMap(firstNonNull(
        templateValue(ctx, "trailer_template"), templateValue(ctx, "trailerTemplate")));
    boolean trailerEnabled = OutboundTrailerRecord.enabled(trailerTemplate);
    boolean resuming = isResuming(ctx);
    String amountField = trailerEnabled ? OutboundTrailerRecord.amountField(trailerTemplate) : null;
    BigDecimal resumeControlTotal =
        trailerEnabled && resuming && amountField != null ? batchControlTotal(ctx) : null;
    if (trailerEnabled && resuming && amountField != null && resumeControlTotal == null) {
      throw new IllegalStateException(
          "DELIMITED trailer control total cannot resume without batch total_amount/totalAmount");
    }
    String accumulationField = resuming ? null : amountField;
    BigDecimal[] controlTotal = {amountField == null ? null : BigDecimal.ZERO};
    if (resumeControlTotal != null) {
      controlTotal[0] = resumeControlTotal;
    }

    // ADR-038 P3:首页仅用于列解析(只读、幂等);续跑时 generatePaged 会忽略它、从 resumeCursor 续拉。
    try (AutoCloseable ignored = strictEncoderScope();
        ResumableExportFile file = openExportFile(ctx)) {
      BufferedWriter writer = file.writer();
      // 续跑时残文件已含表头,不可重写。
      if (!resuming) {
        writeDelimitedHeaderRows(writer, columns, formatConfig, ctx);
      }
      long recordCount =
          generatePaged(ctx, firstPage, file::flushAndSync, (batch, detail, rowIndex) -> {
            writeDelimitedFields(
                writer, columns, batch, detail, formatConfig, "data-row-" + (rowIndex + 1), ctx);
            writeNewLine(writer, ctx);
            if (accumulationField != null) {
              BigDecimal value = decimalValue(detail.get(accumulationField));
              if (value != null) {
                controlTotal[0] = controlTotal[0].add(value);
              }
            }
            if (ctx.chunkSize() > 0 && (rowIndex + 1) % ctx.chunkSize() == 0) {
              writer.flush();
            }
          });
      if (trailerEnabled) {
        writeDelimitedTrailer(
            writer, trailerTemplate, recordCount, controlTotal[0], formatConfig, ctx);
      }
      return recordCount;
    }
  }

  private void writeDelimitedTrailer(
      BufferedWriter writer,
      Map<String, Object> trailerTemplate,
      long recordCount,
      BigDecimal controlTotal,
      DelimitedFormatConfig formatConfig,
      ExportFormatContext ctx)
      throws IOException {
    List<String> values =
        OutboundTrailerRecord.buildValues(trailerTemplate, recordCount, controlTotal);
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        writer.write(formatConfig.delimiter());
      }
      writeText(writer, csv(values.get(i), formatConfig), "trailer", "trailer-" + (i + 1), ctx);
    }
    writeNewLine(writer, ctx);
  }

  private Object templateValue(ExportFormatContext ctx, String key) {
    Map<String, Object> templateConfig = ctx.dataCtx().templateConfig();
    return templateConfig == null ? null : templateConfig.get(key);
  }

  private BigDecimal batchControlTotal(ExportFormatContext ctx) {
    Map<String, Object> batch = ctx.batch();
    if (batch == null || batch.isEmpty()) {
      return null;
    }
    return decimalValue(firstNonNull(
        batch.get("total_amount"),
        batch.get("totalAmount"),
        batch.get("control_total"),
        batch.get("controlTotal")));
  }

  private void writeDelimitedHeaderRows(
      BufferedWriter writer,
      List<ColumnLayout> columns,
      DelimitedFormatConfig formatConfig,
      ExportFormatContext ctx)
      throws IOException {
    int headerRows = Math.max(1, formatConfig.headerRows());
    for (int i = 0; i < headerRows; i++) {
      String rowLabel = "header-" + (i + 1);
      for (int j = 0; j < columns.size(); j++) {
        if (j > 0) {
          writer.write(formatConfig.delimiter());
        }
        ColumnLayout column = columns.get(j);
        writeText(writer, csv(column.header(), formatConfig), rowLabel, column.source(), ctx);
      }
      writeNewLine(writer, ctx);
    }
  }

  private void writeDelimitedFields(
      BufferedWriter writer,
      List<ColumnLayout> columns,
      Map<String, Object> batch,
      Map<String, Object> detail,
      DelimitedFormatConfig formatConfig,
      String rowLabel,
      ExportFormatContext ctx)
      throws IOException {
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        writer.write(formatConfig.delimiter());
      }
      ColumnLayout column = columns.get(i);
      String value = csv(resolveDelimitedValue(batch, detail, column.source()), formatConfig);
      writeText(writer, value, rowLabel, column.source(), ctx);
    }
  }
}
