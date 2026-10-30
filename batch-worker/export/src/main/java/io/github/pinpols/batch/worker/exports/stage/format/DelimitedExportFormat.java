package io.github.pinpols.batch.worker.exports.stage.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    return FileTemplateFormat.DELIMITED.code();
  }

  @Override
  public long generate(ExportFormatContext ctx) throws Exception {
    Long batchIdLong;
    if (EmptyChecks.isNull(ctx.batchId())) {
      batchIdLong = null;
    } else {
      Object rawBatchId = ctx.batchId();
      String batchIdText = String.valueOf(rawBatchId);
      batchIdLong = Long.valueOf(batchIdText);
    }
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
    Object trailerTemplateValue =
        firstNonNull(templateValue(ctx, "trailer_template"), templateValue(ctx, "trailerTemplate"));
    Map<String, Object> trailerTemplate = toMap(trailerTemplateValue);
    boolean trailerEnabled = OutboundTrailerRecord.enabled(trailerTemplate);
    boolean resuming = isResuming(ctx);
    String amountField = trailerEnabled ? OutboundTrailerRecord.amountField(trailerTemplate) : null;
    BigDecimal resumeControlTotal =
        trailerEnabled && resuming && amountField != null ? batchControlTotal(ctx) : null;
    boolean missingResumeControlTotal = trailerEnabled
        && resuming
        && EmptyChecks.isNotNull(amountField)
        && EmptyChecks.isNull(resumeControlTotal);
    if (missingResumeControlTotal) {
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
      DelimitedOutput output = new DelimitedOutput(writer, columns, formatConfig, ctx);
      long recordCount = ExportPageGenerationCoordinator.generatePaged(
          ctx, firstPage, file::flushAndSync, (batch, detail, rowIndex) -> {
            writeDelimitedFields(output, batch, detail, "data-row-" + (rowIndex + 1));
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
      String trailerValue = values.get(i);
      String encodedTrailerValue = csv(trailerValue, formatConfig);
      writeText(writer, encodedTrailerValue, "trailer", "trailer-" + (i + 1), ctx);
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
    Object configuredControlTotal = firstNonNull(
        batch.get("total_amount"),
        batch.get("totalAmount"),
        batch.get("control_total"),
        batch.get("controlTotal"));
    return decimalValue(configuredControlTotal);
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
        String encodedHeader = csv(column.header(), formatConfig);
        writeText(writer, encodedHeader, rowLabel, column.source(), ctx);
      }
      writeNewLine(writer, ctx);
    }
  }

  /** 同一输出文件的列布局与编码策略固定，逐行回调不重新装配资源。 */
  private record DelimitedOutput(
      BufferedWriter writer,
      List<ColumnLayout> columns,
      DelimitedFormatConfig formatConfig,
      ExportFormatContext context) {
    private DelimitedOutput {
      Objects.requireNonNull(writer, "writer");
      Objects.requireNonNull(columns, "columns");
      Objects.requireNonNull(formatConfig, "formatConfig");
      Objects.requireNonNull(context, "context");
    }
  }

  private void writeDelimitedFields(
      DelimitedOutput output,
      Map<String, Object> batch,
      Map<String, Object> detail,
      String rowLabel)
      throws IOException {
    BufferedWriter writer = output.writer();
    List<ColumnLayout> columns = output.columns();
    DelimitedFormatConfig formatConfig = output.formatConfig();
    ExportFormatContext ctx = output.context();
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        writer.write(formatConfig.delimiter());
      }
      ColumnLayout column = columns.get(i);
      Object resolvedValue = resolveDelimitedValue(batch, detail, column.source());
      String value = csv(resolvedValue, formatConfig);
      writeText(writer, value, rowLabel, column.source(), ctx);
    }
  }
}
