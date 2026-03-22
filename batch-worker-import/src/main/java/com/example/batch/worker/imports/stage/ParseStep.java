package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ParseStep implements ImportStageStep {

    private final ObjectMapper objectMapper;
    private final PlatformFileRuntimeRepository runtimeRepository;
    private final ImportRecordGovernanceService recordGovernanceService;

    @Override
    public ImportStage stage() {
        return ImportStage.PARSE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        try {
            String payloadText = String.valueOf(context.getAttributes().getOrDefault("normalizedPayload", context.getRawPayload()));
            ImportPayload importPayload = context.getAttributes().get("importPayload") instanceof ImportPayload payload ? payload : null;
            List<CustomerImportPayload> payloads = new ArrayList<>();
            long totalCount = parsePayloads(context, payloadText, importPayload, context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG), payloads);
            context.getAttributes().put("customerPayloads", payloads);
            context.getAttributes().put("parsedCount", payloads.size());
            context.getAttributes().put("totalCount", totalCount);
            if (payloads.isEmpty() && totalCount == 0) {
                return ImportStageResult.failure(stage(), "IMPORT_PARSE_EMPTY", "no customer records parsed");
            }
            if (!recordGovernanceService.withinThreshold(context)) {
                return ImportStageResult.failure(stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
            }
            runtimeRepository.updateFileStatus(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                    "PARSED",
                    Map.of(
                            "parsedCount", payloads.size(),
                            "totalCount", totalCount,
                            "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                            "badRecordCount", badRecordCount(context)
                    )
            );
            if (!payloads.isEmpty()) {
                context.getAttributes().put("customerPayload", payloads.get(0));
            }
            return ImportStageResult.success(stage());
        } catch (Exception ex) {
            return ImportStageResult.failure(stage(), "IMPORT_PARSE_FAILED", ex.getMessage());
        }
    }

    private long parsePayloads(ImportJobContext context,
                               String payloadText,
                               ImportPayload importPayload,
                               Object templateConfigObject,
                               List<CustomerImportPayload> payloads) throws Exception {
        String format = resolveFormat(importPayload, templateConfigObject, payloadText);
        if ("JSON".equalsIgnoreCase(format)) {
            return parseJsonPayloads(context, payloadText, payloads);
        }
        return parseDelimitedPayloads(context, payloadText, importPayload, templateConfigObject, payloads);
    }

    private long parseJsonPayloads(ImportJobContext context, String payloadText, List<CustomerImportPayload> payloads) throws Exception {
        JsonNode root = objectMapper.readTree(payloadText);
        if (root == null || root.isNull()) {
            return 0L;
        }
        long recordNo = 0L;
        if (root.isArray()) {
            for (JsonNode node : root) {
                recordNo++;
                collectSchemaFields(context, node);
                parseJsonRecord(context, payloads, node, recordNo);
            }
            return recordNo;
        }
        JsonNode recordsNode = root.get("records");
        if (recordsNode != null && recordsNode.isArray()) {
            for (JsonNode node : recordsNode) {
                recordNo++;
                collectSchemaFields(context, node);
                parseJsonRecord(context, payloads, node, recordNo);
            }
            return recordNo;
        }
        collectSchemaFields(context, root);
        parseJsonRecord(context, payloads, root, 1L);
        return 1L;
    }

    private void parseJsonRecord(ImportJobContext context, List<CustomerImportPayload> payloads, JsonNode node, long recordNo) {
        try {
            CustomerImportPayload payload = objectMapper.treeToValue(node, CustomerImportPayload.class);
            if (payload == null) {
                recordParseError(context, recordNo, "IMPORT_PARSE_JSON_INVALID", "json payload is empty", node);
                return;
            }
            payloads.add(payload);
        } catch (Exception exception) {
            recordParseError(context, recordNo, "IMPORT_PARSE_JSON_INVALID", exception.getMessage(), node);
        }
    }

    private long parseDelimitedPayloads(ImportJobContext context,
                                        String payloadText,
                                        ImportPayload importPayload,
                                        Object templateConfigObject,
                                        List<CustomerImportPayload> payloads) {
        if (!StringUtils.hasText(payloadText)) {
            return 0L;
        }
        String[] rawLines = payloadText.split("\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (StringUtils.hasText(line)) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return 0L;
        }
        int footerRows = resolveInt(importPayload == null ? null : importPayload.footerRows(), templateConfigObject, "footer_rows", 0);
        int headerRows = resolveInt(importPayload == null ? null : importPayload.headerRows(), templateConfigObject, "header_rows", 0);
        boolean withHeader = importPayload != null && importPayload.withHeader() != null
                ? importPayload.withHeader()
                : headerRows > 0;
        String delimiter = resolveDelimiter(importPayload, templateConfigObject);
        List<String> headers = defaultHeaders();
        int dataStartIndex = 0;
        if (withHeader || headerRows > 0) {
            headers = parseDelimitedLine(lines.get(0), delimiter);
            dataStartIndex = Math.max(headerRows, 1);
        }
        context.getAttributes().put("schemaFields", new ArrayList<>(headers));
        int dataEndExclusive = Math.max(dataStartIndex, lines.size() - footerRows);
        long recordNo = dataStartIndex + 1L;
        for (int index = dataStartIndex; index < dataEndExclusive; index++) {
            String line = lines.get(index);
            try {
                List<String> columns = parseDelimitedLine(line, delimiter);
                if (columns.isEmpty()) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_LINE_INVALID", "empty line", line);
                    recordNo++;
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < columns.size() ? columns.get(i) : null);
                }
                CustomerImportPayload payload = objectMapper.convertValue(row, CustomerImportPayload.class);
                if (payload == null) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_LINE_INVALID", "line cannot convert to payload", row);
                } else {
                    payloads.add(payload);
                }
            } catch (Exception exception) {
                recordParseError(context, recordNo, "IMPORT_PARSE_LINE_INVALID", exception.getMessage(), line);
            }
            recordNo++;
        }
        return Math.max(0, dataEndExclusive - dataStartIndex);
    }

    private void recordParseError(ImportJobContext context, long recordNo, String errorCode, String message, Object rawRecord) {
        if (!recordGovernanceService.isSkippable(errorCode)) {
            recordGovernanceService.recordFailedRecord(context, stage(), recordNo, errorCode, message, rawRecord);
            throw new IllegalStateException(message);
        }
        recordGovernanceService.recordSkippedRecord(context, stage(), recordNo, errorCode, message, rawRecord);
        if (recordGovernanceService.shouldFailOnSkip(errorCode)) {
            throw new IllegalStateException("skip action FAIL_BATCH");
        }
    }

    private List<String> parseDelimitedLine(String line, String delimiter) {
        List<String> columns = new ArrayList<>();
        if (line == null) {
            return columns;
        }
        char delimiterChar = delimiter.charAt(0);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char currentChar = line.charAt(i);
            if (currentChar == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && currentChar == delimiterChar) {
                columns.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(currentChar);
        }
        columns.add(current.toString().trim());
        return columns;
    }

    private List<String> defaultHeaders() {
        return List.of("customerNo", "customerName", "customerType", "certificateNo", "mobileNo", "email", "status");
    }

    private String resolveFormat(ImportPayload importPayload, Object templateConfigObject, String payloadText) {
        if (importPayload != null && StringUtils.hasText(importPayload.fileFormatType())) {
            return importPayload.fileFormatType();
        }
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object value = templateConfig.get("file_format_type");
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return payloadText != null && payloadText.trim().startsWith("{") || payloadText != null && payloadText.trim().startsWith("[")
                ? "JSON"
                : "DELIMITED";
    }

    private String resolveDelimiter(ImportPayload importPayload, Object templateConfigObject) {
        if (importPayload != null && StringUtils.hasText(importPayload.delimiter())) {
            return importPayload.delimiter();
        }
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object value = templateConfig.get("delimiter");
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return ",";
    }

    private int resolveInt(Integer overrideValue, Object templateConfigObject, String key, int defaultValue) {
        if (overrideValue != null) {
            return overrideValue;
        }
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object value = templateConfig.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return Integer.parseInt(String.valueOf(value));
            }
        }
        return defaultValue;
    }

    private long badRecordCount(ImportJobContext context) {
        Object value = context.getAttributes().get("badRecords");
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0L;
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text)) {
            return 0L;
        }
        return Long.parseLong(text);
    }

    @SuppressWarnings("unchecked")
    private void collectSchemaFields(ImportJobContext context, JsonNode node) {
        if (context == null || node == null || !node.isObject()) {
            return;
        }
        Object existing = context.getAttributes().get("schemaFields");
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    fields.add(String.valueOf(item));
                }
            }
        }
        node.fieldNames().forEachRemaining(fields::add);
        context.getAttributes().put("schemaFields", new ArrayList<>(fields));
    }
}
