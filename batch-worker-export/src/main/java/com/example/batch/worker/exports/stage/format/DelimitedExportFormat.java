package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Generates a delimited flat-file (CSV / TSV / custom delimiter) with optional quoting and escaping.
 *
 * <p>Column layout and format configuration are resolved from the template config;
 * header rows are written before data rows.
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
        Object cursor = null;
        ExportDataPlugin.DetailPage page = ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), cursor);
        List<ColumnLayout> columns = resolveDelimitedColumns(ctx.dataCtx(), ctx.dataPlugin(), ctx.batch(), page.rows());
        if (columns.isEmpty() && page.rows().isEmpty()) {
            return 0L;
        }
        DelimitedFormatConfig formatConfig = resolveDelimitedFormatConfig(ctx.dataCtx().templateConfig());

        try (BufferedWriter writer = Files.newBufferedWriter(ctx.generatedFile(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeDelimitedHeaderRows(writer, columns, formatConfig);
            long recordCount = 0L;
            while (true) {
                List<Map<String, Object>> details = page.rows();
                if (details.isEmpty()) {
                    break;
                }
                for (Map<String, Object> detail : details) {
                    StringJoiner joiner = new StringJoiner(formatConfig.delimiter());
                    for (ColumnLayout column : columns) {
                        joiner.add(csv(resolveDelimitedValue(ctx.batch(), detail, column.source()), formatConfig));
                    }
                    writer.write(joiner.toString());
                    writer.newLine();
                    recordCount++;
                    if (ctx.chunkSize() > 0 && recordCount % ctx.chunkSize() == 0) {
                        writer.flush();
                    }
                }
                cursor = page.nextCursor();
                if (cursor == null) {
                    break;
                }
                page = ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), cursor);
            }
            return recordCount;
        }
    }

    private void writeDelimitedHeaderRows(BufferedWriter writer,
                                          List<ColumnLayout> columns,
                                          DelimitedFormatConfig formatConfig) throws Exception {
        int headerRows = Math.max(1, formatConfig.headerRows());
        String headerLine = buildDelimitedLine(columns.stream().map(ColumnLayout::header).toList(), formatConfig);
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
