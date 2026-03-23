package com.example.batch.worker.exports.stage;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateStep implements ExportStageStep {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ExportDataPluginRegistry exportDataPluginRegistry;
    private final ObjectMapper objectMapper;
    private final ExportWorkerConfiguration workerConfiguration;

    @Override
    public ExportStage stage() {
        return ExportStage.GENERATE;
    }

    @Override
    public ExportStageResult execute(ExportJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("exportPayload");
        if (!(payload instanceof ExportPayload exportPayload) || !StringUtils.hasText(exportPayload.batchNo())) {
            return ExportStageResult.failure(stage(), "EXPORT_GENERATE_NO_PAYLOAD", "export payload missing");
        }
        Path generatedFile = null;
        try {
            String exportDataRef = resolveExportDataRef(context, exportPayload);
            context.getAttributes().put("exportDataRef", exportDataRef);
            ExportDataContext dataCtx = buildExportDataContext(context, exportPayload);
            ExportDataPlugin dataPlugin = exportDataPluginRegistry.require(exportDataRef);
            Map<String, Object> batch = dataPlugin.loadBatch(dataCtx);
            if (batch.isEmpty()) {
                return ExportStageResult.failure(stage(), "EXPORT_BATCH_NOT_FOUND", "export batch not found");
            }
            Object batchId = batch.get("id");
            long recordCount = 0L;
            int pageSize = resolvePageSize(context);
            int chunkSize = resolveChunkSize(context);
            String fileFormatType = String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
            generatedFile = createGeneratedFile(context, exportPayload, fileFormatType);
            if ("DELIMITED".equalsIgnoreCase(fileFormatType)) {
                try (BufferedWriter writer = Files.newBufferedWriter(generatedFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    recordCount = generateDelimited(batch, batchId, pageSize, chunkSize, writer, context, dataPlugin, dataCtx);
                }
            } else if ("FIXED_WIDTH".equalsIgnoreCase(fileFormatType)) {
                recordCount = generateFixedWidth(batch, batchId, pageSize, chunkSize, generatedFile, context, dataPlugin, dataCtx);
            } else if ("EXCEL".equalsIgnoreCase(fileFormatType)) {
                recordCount = generateExcel(batch, batchId, pageSize, chunkSize, generatedFile, context, dataPlugin, dataCtx);
            } else {
                try (BufferedWriter writer = Files.newBufferedWriter(generatedFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    recordCount = generateJson(batch, batchId, pageSize, chunkSize, writer, context, dataPlugin, dataCtx);
                }
            }
            context.getAttributes().put("exportBatch", batch);
            context.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, generatedFile.toString());
            context.getAttributes().put("recordCount", recordCount);
            context.getAttributes().put("totalAmount", batch.getOrDefault("total_amount", BigDecimal.ZERO));
            context.getAttributes().put("fileSizeBytes", Files.size(generatedFile));
            return ExportStageResult.success(stage());
        } catch (Exception ex) {
            deleteQuietly(generatedFile);
            return ExportStageResult.failure(stage(), "EXPORT_GENERATE_FAILED", ex.getMessage());
        }
    }

    private ExportDataContext buildExportDataContext(ExportJobContext context, ExportPayload exportPayload) {
        Map<String, Object> tc = templateConfigMap(context);
        Object snap = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
        Map<String, Object> snapMap = new LinkedHashMap<>();
        if (snap instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> snapMap.put(String.valueOf(k), v));
        }
        return new ExportDataContext(
                context.getTenantId(),
                context.getJobCode(),
                exportPayload.batchNo(),
                exportPayload.templateCode(),
                tc,
                snapMap
        );
    }

    private Map<String, Object> templateConfigMap(ExportJobContext context) {
        Object o = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private String resolveExportDataRef(ExportJobContext context, ExportPayload exportPayload) {
        Map<String, Object> tc = templateConfigMap(context);
        Object v = tc.get("export_data_ref");
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            return String.valueOf(v).trim();
        }
        return WorkerPluginIds.EXPORT_DATA_SETTLEMENT;
    }

    private long generateJson(Map<String, Object> batch,
                              Object batchId,
                              int pageSize,
                              int chunkSize,
                              BufferedWriter writer,
                              ExportJobContext context,
                              ExportDataPlugin dataPlugin,
                              ExportDataContext dataCtx) throws Exception {
        Object snapshot = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
        if (snapshot == null) {
            snapshot = Map.of();
        }
        writer.write("{\"snapshot\":");
        writeJsonValue(writer, snapshot);
        writer.write(",\"batch\":");
        writeJsonValue(writer, batch);
        writer.write(",\"details\":[");
        long recordCount = 0L;
        boolean first = true;
        Object cursor = null;
        Long batchIdLong = batchId == null ? null : Long.valueOf(String.valueOf(batchId));
        while (true) {
            ExportDataPlugin.DetailPage page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, cursor);
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
                if (chunkSize > 0 && recordCount % chunkSize == 0) {
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

    /** Writes one JSON value without closing the shared NDJSON/aggregate file writer. */
    private void writeJsonValue(Writer writer, Object value) throws IOException {
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(writer)) {
            generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
            objectMapper.writeValue(generator, value);
        }
    }

    private long generateDelimited(Map<String, Object> batch,
                                   Object batchId,
                                   int pageSize,
                                   int chunkSize,
                                   BufferedWriter writer,
                                   ExportJobContext context,
                                   ExportDataPlugin dataPlugin,
                                   ExportDataContext dataCtx) throws Exception {
        Long batchIdLong = batchId == null ? null : Long.valueOf(String.valueOf(batchId));
        Object cursor = null;
        ExportDataPlugin.DetailPage page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, cursor);
        List<ColumnLayout> columns = resolveDelimitedColumns(dataCtx, dataPlugin, batch, page.rows());
        if (columns.isEmpty() && page.rows().isEmpty()) {
            return 0L;
        }
        DelimitedFormatConfig formatConfig = resolveDelimitedFormatConfig(dataCtx.templateConfig());
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
                    joiner.add(csv(resolveDelimitedValue(batch, detail, column.source()), formatConfig));
                }
                writer.write(joiner.toString());
                writer.newLine();
                recordCount++;
                if (chunkSize > 0 && recordCount % chunkSize == 0) {
                    writer.flush();
                }
            }
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
            page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, cursor);
        }
        return recordCount;
    }

    private long generateExcel(Map<String, Object> batch,
                               Object batchId,
                               int pageSize,
                               int chunkSize,
                               Path generatedFile,
                               ExportJobContext context,
                               ExportDataPlugin dataPlugin,
                               ExportDataContext dataCtx) throws Exception {
        Long batchIdLong = batchId == null ? null : Long.valueOf(String.valueOf(batchId));
        ExportDataPlugin.DetailPage page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, null);
        List<ColumnLayout> columns = resolveExcelColumns(dataCtx, dataPlugin, batch, page.rows());
        DelimitedFormatConfig formatConfig = resolveDelimitedFormatConfig(dataCtx.templateConfig());
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
             OutputStream outputStream = Files.newOutputStream(generatedFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            Sheet sheet = workbook.createSheet(resolveSheetName(dataCtx.templateConfig()));

            int rowNo = 0;
            rowNo = writeExcelHeaderRows(sheet, columns, rowNo, formatConfig.headerRows());
            long recordCount = 0L;
            while (true) {
                List<Map<String, Object>> details = page.rows();
                if (details.isEmpty()) {
                    break;
                }
                for (Map<String, Object> detail : details) {
                    Row row = sheet.createRow(rowNo++);
                    for (int i = 0; i < columns.size(); i++) {
                        Cell cell = row.createCell(i);
                        cell.setCellValue(textValue(resolveDelimitedValue(batch, detail, columns.get(i).source())));
                    }
                    recordCount++;
                }
                Object cursor = page.nextCursor();
                if (cursor == null) {
                    break;
                }
                page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, cursor);
            }
            workbook.write(outputStream);
            workbook.dispose();
            return recordCount;
        }
    }

    private long generateFixedWidth(Map<String, Object> batch,
                                    Object batchId,
                                    int pageSize,
                                    int chunkSize,
                                    Path generatedFile,
                                    ExportJobContext context,
                                    ExportDataPlugin dataPlugin,
                                    ExportDataContext dataCtx) throws Exception {
        Long batchIdLong = batchId == null ? null : Long.valueOf(String.valueOf(batchId));
        Object cursor = null;
        ExportDataPlugin.DetailPage page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, cursor);
        List<ColumnLayout> columns = resolveFixedWidthColumns(dataCtx, dataPlugin, batch, page.rows());
        if (columns.isEmpty() && page.rows().isEmpty()) {
            return 0L;
        }
        int recordLength = resolveTemplateInt(context, "record_length", 0);
        int headerRows = resolveDelimitedFormatConfig(dataCtx.templateConfig()).headerRows();
        try (BufferedWriter writer = Files.newBufferedWriter(generatedFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFixedWidthHeaderRows(writer, columns, recordLength, headerRows);
            long recordCount = 0L;
            while (true) {
                List<Map<String, Object>> details = page.rows();
                if (details.isEmpty()) {
                    break;
                }
                for (Map<String, Object> detail : details) {
                    StringBuilder line = new StringBuilder();
                    for (ColumnLayout column : columns) {
                        line.append(fixedWidth(resolveDelimitedValue(batch, detail, column.source()), column));
                    }
                    if (recordLength > 0) {
                        line = new StringBuilder(padRight(line.toString(), recordLength));
                    }
                    writer.write(line.toString());
                    writer.newLine();
                    recordCount++;
                    if (chunkSize > 0 && recordCount % chunkSize == 0) {
                        writer.flush();
                    }
                }
                cursor = page.nextCursor();
                if (cursor == null) {
                    break;
                }
                page = dataPlugin.loadDetailPage(dataCtx, batchIdLong, pageSize, cursor);
            }
            return recordCount;
        }
    }

    private int resolvePageSize(ExportJobContext context) {
        return resolveTemplateInt(context, "page_size", workerConfiguration == null ? 1000 : workerConfiguration.pageSize());
    }

    private int resolveChunkSize(ExportJobContext context) {
        return resolveTemplateInt(context, "chunk_size", workerConfiguration == null ? 500 : workerConfiguration.chunkSize());
    }

    private int resolveTemplateInt(ExportJobContext context, String key, int fallback) {
        Object templateConfigObject = context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object value = templateConfig.get(key);
            if (value instanceof Number number) {
                return Math.max(1, number.intValue());
            }
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return Math.max(1, Integer.parseInt(String.valueOf(value)));
            }
        }
        return fallback;
    }

    private Path createGeneratedFile(ExportJobContext context, ExportPayload payload, String fileFormatType) throws Exception {
        String suffix = switch (fileFormatType == null ? "" : fileFormatType.toUpperCase()) {
            case "DELIMITED" -> BatchFileConstants.CSV_SUFFIX;
            case "EXCEL" -> BatchFileConstants.XLSX_SUFFIX;
            case "FIXED_WIDTH" -> BatchFileConstants.TXT_SUFFIX;
            default -> BatchFileConstants.JSON_SUFFIX;
        };
        return Files.createTempFile(BatchFileConstants.exportStagePrefix(context.getTenantId(), payload.batchNo()), suffix);
    }

    private List<ColumnLayout> resolveDelimitedColumns(ExportDataContext dataCtx,
                                                       ExportDataPlugin dataPlugin,
                                                       Map<String, Object> batch,
                                                       List<Map<String, Object>> firstPage) {
        List<ColumnLayout> configured = templateDelimitedColumns(dataCtx.templateConfig());
        if (!configured.isEmpty()) {
            return configured;
        }
        List<ExportDataPlugin.DelimitedColumn> pluginColumns = dataPlugin.describeDelimitedColumns(dataCtx, batch);
        if (!pluginColumns.isEmpty()) {
            return pluginColumns.stream().map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' ')).toList();
        }
        if (firstPage == null || firstPage.isEmpty()) {
            return List.of();
        }
        Map<String, Object> first = firstPage.get(0);
        List<ColumnLayout> inferred = new ArrayList<>();
        for (String key : first.keySet()) {
            inferred.add(new ColumnLayout(key, "detail." + key, null, false, ' '));
        }
        return inferred;
    }

    private List<ColumnLayout> resolveExcelColumns(ExportDataContext dataCtx,
                                                   ExportDataPlugin dataPlugin,
                                                   Map<String, Object> batch,
                                                   List<Map<String, Object>> firstPage) {
        return resolveDelimitedColumns(dataCtx, dataPlugin, batch, firstPage);
    }

    private List<ColumnLayout> resolveFixedWidthColumns(ExportDataContext dataCtx,
                                                        ExportDataPlugin dataPlugin,
                                                        Map<String, Object> batch,
                                                        List<Map<String, Object>> firstPage) {
        List<ColumnLayout> configured = templateFixedWidthColumns(dataCtx.templateConfig());
        if (!configured.isEmpty()) {
            return configured;
        }
        List<ExportDataPlugin.DelimitedColumn> pluginColumns = dataPlugin.describeFixedWidthColumns(dataCtx, batch);
        if (!pluginColumns.isEmpty()) {
            return pluginColumns.stream().map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' ')).toList();
        }
        if (firstPage == null || firstPage.isEmpty()) {
            return List.of();
        }
        Map<String, Object> first = firstPage.get(0);
        List<ColumnLayout> inferred = new ArrayList<>();
        for (String key : first.keySet()) {
            inferred.add(new ColumnLayout(key, "detail." + key, Math.max(key.length(), 16), false, ' '));
        }
        return inferred;
    }

    private List<ColumnLayout> templateDelimitedColumns(Map<String, Object> templateConfig) {
        if (templateConfig == null || templateConfig.isEmpty()) {
            return List.of();
        }
        Object direct = firstNonNull(templateConfig.get("csv_columns"), templateConfig.get("csvColumns"));
        List<ColumnLayout> parsed = parseDelimitedColumns(direct, false);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        Object querySchema = templateConfig.get("query_param_schema");
        Map<String, Object> schemaMap = toMap(querySchema);
        return parseDelimitedColumns(firstNonNull(schemaMap.get("csvColumns"), schemaMap.get("delimitedColumns")), false);
    }

    private List<ColumnLayout> templateFixedWidthColumns(Map<String, Object> templateConfig) {
        if (templateConfig == null || templateConfig.isEmpty()) {
            return List.of();
        }
        Object direct = firstNonNull(templateConfig.get("fixed_width_columns"), templateConfig.get("fixedWidthColumns"));
        List<ColumnLayout> parsed = parseDelimitedColumns(direct, true);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        Object querySchema = templateConfig.get("query_param_schema");
        Map<String, Object> schemaMap = toMap(querySchema);
        return parseDelimitedColumns(firstNonNull(schemaMap.get("fixedWidthColumns"), schemaMap.get("fixed_width_columns")), true);
    }

    private List<ColumnLayout> parseDelimitedColumns(Object raw, boolean fixedWidth) {
        if (!(raw instanceof Collection<?> list)) {
            return List.of();
        }
        List<ColumnLayout> columns = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = toMap(item);
            if (map.isEmpty()) {
                continue;
            }
            String source = textValue(firstNonNull(map.get("source"), map.get("path"), map.get("field")));
            if (!StringUtils.hasText(source)) {
                continue;
            }
            String normalizedSource = normalizeDelimitedSource(source);
            String header = textValue(firstNonNull(map.get("header"), map.get("name")));
            if (!StringUtils.hasText(header)) {
                header = defaultDelimitedHeader(normalizedSource);
            }
            Integer width = fixedWidth ? integerValue(firstNonNull(map.get("width"), map.get("size"), map.get("len"))) : null;
            String align = fixedWidth ? textValue(firstNonNull(map.get("align"), map.get("alignment"))) : null;
            String padChar = fixedWidth ? textValue(firstNonNull(map.get("padChar"), map.get("pad_char"))) : null;
            columns.add(new ColumnLayout(header, normalizedSource, width, "RIGHT".equalsIgnoreCase(align), resolvePadChar(padChar)));
        }
        return columns;
    }

    private String normalizeDelimitedSource(String source) {
        String value = source == null ? "" : source.trim();
        if (value.startsWith("batch.") || value.startsWith("detail.")) {
            return value;
        }
        return "detail." + value;
    }

    private String defaultDelimitedHeader(String source) {
        int idx = source.lastIndexOf('.');
        return idx >= 0 && idx + 1 < source.length() ? source.substring(idx + 1) : source;
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

    private int writeExcelHeaderRows(Sheet sheet, List<ColumnLayout> columns, int rowNo, int headerRows) {
        int effectiveHeaderRows = Math.max(1, headerRows);
        for (int i = 0; i < effectiveHeaderRows; i++) {
            Row row = sheet.createRow(rowNo++);
            for (int c = 0; c < columns.size(); c++) {
                row.createCell(c).setCellValue(columns.get(c).header());
            }
        }
        return rowNo;
    }

    private void writeFixedWidthHeaderRows(BufferedWriter writer,
                                           List<ColumnLayout> columns,
                                           int recordLength,
                                           int headerRows) throws Exception {
        int effectiveHeaderRows = Math.max(1, headerRows);
        String header = fixedWidthLine(columns, recordLength, c -> c.header());
        for (int i = 0; i < effectiveHeaderRows; i++) {
            writer.write(header);
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

    private String csv(Object value, DelimitedFormatConfig formatConfig) {
        String text = textValue(value);
        if (!StringUtils.hasText(text)) {
            return "";
        }
        boolean needsQuote = switch (formatConfig.quotePolicy()) {
            case ALL -> true;
            case NONE -> false;
            case REQUIRED -> text.contains(formatConfig.delimiter())
                    || text.contains("\n")
                    || text.contains("\r")
                    || text.contains(formatConfig.quoteChar())
                    || text.startsWith(" ")
                    || text.endsWith(" ");
        };
        String escaped = escapeDelimited(text, formatConfig);
        if (!needsQuote) {
            return escaped;
        }
        return formatConfig.quoteChar() + escaped + formatConfig.quoteChar();
    }

    private String escapeDelimited(String value, DelimitedFormatConfig formatConfig) {
        return switch (formatConfig.escapePolicy()) {
            case BACKSLASH -> value
                    .replace("\\", "\\\\")
                    .replace(formatConfig.quoteChar(), "\\" + formatConfig.quoteChar())
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
            case NONE -> value;
            case DOUBLE_QUOTE -> value.replace(formatConfig.quoteChar(), formatConfig.quoteChar() + formatConfig.quoteChar());
        };
    }

    private String fixedWidthLine(List<ColumnLayout> columns, int recordLength, java.util.function.Function<ColumnLayout, String> valueMapper) {
        StringBuilder builder = new StringBuilder();
        for (ColumnLayout column : columns) {
            builder.append(fixedWidth(valueMapper.apply(column), column));
        }
        if (recordLength > 0) {
            return padRight(builder.toString(), recordLength);
        }
        return builder.toString();
    }

    private String fixedWidth(Object value, ColumnLayout column) {
        String text = textValue(value);
        int width = column.width() == null || column.width() <= 0 ? Math.max(column.header().length(), 16) : column.width();
        if (text == null) {
            text = "";
        }
        if (text.length() > width) {
            return text.substring(0, width);
        }
        char pad = column.padChar();
        String padding = String.valueOf(pad).repeat(width - text.length());
        if (column.rightAlign()) {
            return padding + text;
        }
        return text + padding;
    }

    private String padRight(String text, int length) {
        String value = text == null ? "" : text;
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    private DelimitedFormatConfig resolveDelimitedFormatConfig(Map<String, Object> templateConfig) {
        Map<String, Object> source = templateConfig == null ? Map.of() : templateConfig;
        Object schema = source.get("query_param_schema");
        Map<String, Object> schemaMap = toMap(schema);
        Object delimiterRaw = firstNonNull(source.get("delimiter"), source.get("quote_delimiter"), schemaMap.get("delimiter"));
        String delimiter = delimiterRaw == null ? null : String.valueOf(delimiterRaw);
        if (delimiter == null || delimiter.isEmpty()) {
            delimiter = ",";
        }
        Object quoteCharRaw = firstNonNull(source.get("quote_char"), source.get("quoteChar"), schemaMap.get("quoteChar"));
        String quoteChar = quoteCharRaw == null ? null : String.valueOf(quoteCharRaw);
        if (quoteChar == null || quoteChar.isEmpty()) {
            quoteChar = "\"";
        }
        QuotePolicy quotePolicy = QuotePolicy.from(firstNonNull(source.get("quote_policy"), source.get("quotePolicy"), schemaMap.get("quotePolicy")));
        EscapePolicy escapePolicy = EscapePolicy.from(firstNonNull(source.get("escape_policy"), source.get("escapePolicy"), schemaMap.get("escapePolicy")));
        int headerRows = resolveIntValue(firstNonNull(source.get("header_rows"), source.get("headerRows"), schemaMap.get("headerRows")), 1);
        return new DelimitedFormatConfig(delimiter, quoteChar, quotePolicy, escapePolicy, headerRows);
    }

    private String resolveSheetName(Map<String, Object> templateConfig) {
        if (templateConfig == null || templateConfig.isEmpty()) {
            return "Sheet1";
        }
        Object v = firstNonNull(templateConfig.get("sheet_name"), templateConfig.get("sheetName"));
        String text = textValue(v);
        return StringUtils.hasText(text) ? sanitizeSheetName(text) : "Sheet1";
    }

    private String sanitizeSheetName(String value) {
        String cleaned = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private Object resolveDelimitedValue(Map<String, Object> batch, Map<String, Object> detail, String source) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        if (source.startsWith("batch.")) {
            return batch.get(source.substring("batch.".length()));
        }
        if (source.startsWith("detail.")) {
            return detail.get(source.substring("detail.".length()));
        }
        Object detailValue = detail.get(source);
        return detailValue != null ? detailValue : batch.get(source);
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), v));
            return converted;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, MAP_TYPE);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ex) {
            log.warn("Failed to delete temp file: {}", path, ex);
        }
    }

    private int resolveIntValue(Object value, int fallback) {
        Integer resolved = integerValue(value);
        return resolved == null ? fallback : Math.max(1, resolved);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private char resolvePadChar(String padChar) {
        if (!StringUtils.hasText(padChar)) {
            return ' ';
        }
        return padChar.charAt(0);
    }

    private record ColumnLayout(String header, String source, Integer width, boolean rightAlign, char padChar) {
    }

    private record DelimitedFormatConfig(String delimiter,
                                         String quoteChar,
                                         QuotePolicy quotePolicy,
                                         EscapePolicy escapePolicy,
                                         int headerRows) {
    }

    private enum QuotePolicy {
        NONE,
        REQUIRED,
        ALL;

        static QuotePolicy from(Object value) {
            if (value == null) {
                return REQUIRED;
            }
            try {
                return QuotePolicy.valueOf(String.valueOf(value).trim().toUpperCase());
            } catch (Exception ignored) {
                return REQUIRED;
            }
        }
    }

    private enum EscapePolicy {
        DOUBLE_QUOTE,
        BACKSLASH,
        NONE;

        static EscapePolicy from(Object value) {
            if (value == null) {
                return DOUBLE_QUOTE;
            }
            try {
                return EscapePolicy.valueOf(String.valueOf(value).trim().toUpperCase());
            } catch (Exception ignored) {
                return DOUBLE_QUOTE;
            }
        }
    }
}
