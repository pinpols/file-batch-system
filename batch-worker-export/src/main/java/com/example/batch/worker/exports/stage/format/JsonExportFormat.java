package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generates a single-document JSON file:
 * {@code {"snapshot":…, "batch":…, "details":[…]}}.
 */
@Component
public class JsonExportFormat extends AbstractExportFormat {

    public JsonExportFormat(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String formatType() {
        return "JSON";
    }

    @Override
    public long generate(ExportFormatContext ctx) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(ctx.generatedFile(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            return writeJson(writer, ctx);
        }
    }

    private long writeJson(BufferedWriter writer, ExportFormatContext ctx) throws Exception {
        Object snapshot = ctx.jobContext().getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
        if (snapshot == null) {
            snapshot = Map.of();
        }
        Long batchIdLong = ctx.batchId() == null ? null : Long.valueOf(String.valueOf(ctx.batchId()));
        writer.write("{\"snapshot\":");
        writeJsonValue(writer, snapshot);
        writer.write(",\"batch\":");
        writeJsonValue(writer, ctx.batch());
        writer.write(",\"details\":[");
        long recordCount = 0L;
        boolean first = true;
        Object cursor = null;
        while (true) {
            ExportDataPlugin.DetailPage page = ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), cursor);
            List<Map<String, Object>> details = page.rows();
            if (details.isEmpty()) {
                break;
            }
            for (Map<String, Object> detail : details) {
                if (!first) {
                    writer.write(",");
                }
                writeJsonValue(writer, detail);
                first = false;
                recordCount++;
                if (ctx.chunkSize() > 0 && recordCount % ctx.chunkSize() == 0) {
                    writer.flush();
                }
            }
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        writer.write("]}");
        return recordCount;
    }

    private void writeJsonValue(Writer writer, Object value) throws java.io.IOException {
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(writer)) {
            generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
            objectMapper.writeValue(generator, value);
        }
    }
}
