package com.example.batch.worker.imports.stage;

import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ParseStep implements ImportStageStep {

    private final ObjectMapper objectMapper;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public ParseStep(ObjectMapper objectMapper, PlatformFileRuntimeRepository runtimeRepository) {
        this.objectMapper = objectMapper;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.PARSE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        try {
            String payloadText = String.valueOf(context.getAttributes().getOrDefault("normalizedPayload", context.getRawPayload()));
            ImportPayload importPayload = context.getAttributes().get("importPayload") instanceof ImportPayload payload ? payload : null;
            List<CustomerImportPayload> payloads = parsePayloads(payloadText, importPayload, context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG));
            if (payloads.isEmpty()) {
                return ImportStageResult.failure(stage(), "IMPORT_PARSE_EMPTY", "no customer records parsed");
            }
            context.getAttributes().put("customerPayloads", payloads);
            context.getAttributes().put("customerPayload", payloads.get(0));
            context.getAttributes().put("parsedCount", payloads.size());
            runtimeRepository.updateFileStatus(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                    "PARSED",
                    Map.of("parsedCount", payloads.size())
            );
        } catch (Exception ex) {
            return ImportStageResult.failure(stage(), "IMPORT_PARSE_FAILED", ex.getMessage());
        }
        return ImportStageResult.success(stage());
    }

    private List<CustomerImportPayload> parsePayloads(String payloadText,
                                                      ImportPayload importPayload,
                                                      Object templateConfigObject) throws Exception {
        String format = resolveFormat(importPayload, templateConfigObject, payloadText);
        if ("JSON".equalsIgnoreCase(format)) {
            return parseJsonPayloads(payloadText);
        }
        return parseDelimitedPayloads(payloadText, importPayload, templateConfigObject);
    }

    private List<CustomerImportPayload> parseJsonPayloads(String payloadText) throws Exception {
        JsonNode root = objectMapper.readTree(payloadText);
        List<CustomerImportPayload> payloads = new ArrayList<>();
        if (root == null || root.isNull()) {
            return payloads;
        }
        if (root.isArray()) {
            for (JsonNode node : root) {
                payloads.add(objectMapper.treeToValue(node, CustomerImportPayload.class));
            }
            return payloads;
        }
        JsonNode recordsNode = root.get("records");
        if (recordsNode != null && recordsNode.isArray()) {
            for (JsonNode node : recordsNode) {
                payloads.add(objectMapper.treeToValue(node, CustomerImportPayload.class));
            }
            return payloads;
        }
        payloads.add(objectMapper.treeToValue(root, CustomerImportPayload.class));
        return payloads;
    }

    private List<CustomerImportPayload> parseDelimitedPayloads(String payloadText,
                                                               ImportPayload importPayload,
                                                               Object templateConfigObject) {
        List<CustomerImportPayload> payloads = new ArrayList<>();
        if (!StringUtils.hasText(payloadText)) {
            return payloads;
        }
        String[] rawLines = payloadText.split("\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (StringUtils.hasText(line)) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return payloads;
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
        int dataEndExclusive = Math.max(dataStartIndex, lines.size() - footerRows);
        for (int index = dataStartIndex; index < dataEndExclusive; index++) {
            List<String> columns = parseDelimitedLine(lines.get(index), delimiter);
            if (columns.isEmpty()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < columns.size() ? columns.get(i) : null);
            }
            payloads.add(objectMapper.convertValue(row, CustomerImportPayload.class));
        }
        return payloads;
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
}
