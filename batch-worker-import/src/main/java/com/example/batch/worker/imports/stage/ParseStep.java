package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParseStep implements ImportStageStep {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final PlatformFileRuntimeRepository runtimeRepository;
    private final ImportRecordGovernanceService recordGovernanceService;

    @Override
    public ImportStage stage() {
        return ImportStage.PARSE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        Path stagingFile = null;
        try {
            String payloadText = String.valueOf(context.getAttributes().getOrDefault("normalizedPayload", context.getRawPayload()));
            ImportPayload importPayload = context.getAttributes().get("importPayload") instanceof ImportPayload payload ? payload : null;
            stagingFile = createStagingFile(context, "parsed");
            long totalCount = parsePayloads(context, payloadText, importPayload, context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG), stagingFile);
            context.getAttributes().put(PipelineRuntimeKeys.PARSED_RECORDS_PATH, stagingFile.toString());
            context.getAttributes().put("parsedCount", numberValue(context.getAttributes().get("parsedCount")));
            context.getAttributes().put("totalCount", totalCount);
            if (totalCount == 0 && numberValue(context.getAttributes().get("skippedCount")) == 0) {
                deleteQuietly(stagingFile);
                return ImportStageResult.failure(stage(), "IMPORT_PARSE_EMPTY", "no records parsed");
            }
            if (!recordGovernanceService.withinThreshold(context)) {
                deleteQuietly(stagingFile);
                return ImportStageResult.failure(stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
            }
            runtimeRepository.updateFileStatus(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                    "PARSED",
                    Map.of(
                            "parsedCount", numberValue(context.getAttributes().get("parsedCount")),
                            "totalCount", totalCount,
                            "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                            "badRecordCount", badRecordCount(context),
                            "parsedRecordsPath", stagingFile.toString()
                    )
            );
            return ImportStageResult.success(stage());
        } catch (Exception ex) {
            deleteQuietly(stagingFile);
            log.error("parse stage failed: tenantId={}, fileId={}, message={}",
                    context == null ? null : context.getTenantId(),
                    context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
                    ex.getMessage(),
                    ex);
            return ImportStageResult.failure(stage(), "IMPORT_PARSE_FAILED", ex.getMessage());
        }
    }

    private long parsePayloads(ImportJobContext context,
                               String payloadText,
                               ImportPayload importPayload,
                               Object templateConfigObject,
                               Path stagingFile) throws Exception {
        boolean preserveLogicalRow = preserveLogicalRow(context, templateConfigObject);
        Object binary = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
        try (BufferedWriter writer = Files.newBufferedWriter(stagingFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            if (binary instanceof byte[] excelBytes && excelBytes.length > 0) {
                String format = resolveFormat(importPayload, templateConfigObject, "");
                if ("EXCEL".equalsIgnoreCase(format)) {
                    return parseExcelPayloads(context, excelBytes, importPayload, templateConfigObject, writer, preserveLogicalRow);
                }
                Charset cs = resolvePayloadTextCharset(importPayload, templateConfigObject);
                String asText = new String(excelBytes, cs);
                return parsePayloadsFromText(context, asText, importPayload, templateConfigObject, writer, preserveLogicalRow);
            }
            return parsePayloadsFromText(context, payloadText, importPayload, templateConfigObject, writer, preserveLogicalRow);
        }
    }

    private long parsePayloadsFromText(ImportJobContext context,
                                       String payloadText,
                                       ImportPayload importPayload,
                                       Object templateConfigObject,
                                       BufferedWriter writer,
                                       boolean preserveLogicalRow) throws Exception {
        String format = resolveFormat(importPayload, templateConfigObject, payloadText);
        if ("JSON".equalsIgnoreCase(format)) {
            return parseJsonPayloads(context, payloadText, writer, preserveLogicalRow);
        }
        if ("EXCEL".equalsIgnoreCase(format)) {
            return parseExcelPayloads(context, payloadText.getBytes(StandardCharsets.UTF_8), importPayload, templateConfigObject, writer, preserveLogicalRow);
        }
        if ("XML".equalsIgnoreCase(format)) {
            return parseXmlPayloads(context, payloadText, templateConfigObject, writer, preserveLogicalRow);
        }
        if ("FIXED_WIDTH".equalsIgnoreCase(format)) {
            return parseFixedWidthPayloads(context, payloadText, importPayload, templateConfigObject, writer, preserveLogicalRow);
        }
        return parseDelimitedPayloads(context, payloadText, importPayload, templateConfigObject, writer, preserveLogicalRow);
    }

    private Charset resolvePayloadTextCharset(ImportPayload importPayload, Object templateConfigObject) {
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object charset = templateConfig.get("charset");
            if (charset != null && StringUtils.hasText(String.valueOf(charset))) {
                return Charset.forName(String.valueOf(charset));
            }
        }
        if (importPayload != null && StringUtils.hasText(importPayload.charset())) {
            return Charset.forName(importPayload.charset());
        }
        return StandardCharsets.UTF_8;
    }

    private long parseExcelPayloads(ImportJobContext context,
                                    byte[] excelBytes,
                                    ImportPayload importPayload,
                                    Object templateConfigObject,
                                    BufferedWriter writer,
                                    boolean preserveLogicalRow) throws Exception {
        int sheetIndex = resolveExcelSheetIndex(templateConfigObject);
        int headerRows = resolveInt(importPayload == null ? null : importPayload.headerRows(), templateConfigObject, "header_rows", 1);
        List<ColumnBinding> bindings = loadColumnBindings(templateFieldMappings(templateConfigObject));
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            DataFormatter formatter = new DataFormatter();
            int firstDataRowIndex = Math.max(headerRows, 0);
            List<String> headers;
            if (headerRows > 0) {
                Row headerRow = sheet.getRow(headerRows - 1);
                headers = readExcelHeader(headerRow, formatter);
            } else {
                headers = defaultHeaders();
            }
            long recordNo = 0L;
            int lastRow = sheet.getLastRowNum();
            for (int r = firstDataRowIndex; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null || rowIsBlank(row, formatter)) {
                    continue;
                }
                recordNo++;
                try {
                    Map<String, String> rowMap = buildExcelRowMap(row, headers, bindings, formatter);
                    collectSchemaFields(context, rowMap);
                    writeParsedRecord(new WriteParsedRecordContext(context, writer, rowMap, preserveLogicalRow, recordNo, "IMPORT_PARSE_EXCEL_INVALID", rowMap));
                } catch (Exception exception) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_EXCEL_INVALID", exception.getMessage(), row);
                }
            }
            return recordNo;
        }
    }

    private List<String> readExcelHeader(Row headerRow, DataFormatter formatter) {
        List<String> headers = new ArrayList<>();
        if (headerRow == null) {
            return defaultHeaders();
        }
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            headers.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return headers.isEmpty() ? defaultHeaders() : headers;
    }

    private boolean rowIsBlank(Row row, DataFormatter formatter) {
        if (row == null) {
            return true;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && StringUtils.hasText(formatter.formatCellValue(cell).trim())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> buildExcelRowMap(Row row,
                                                   List<String> headers,
                                                   List<ColumnBinding> bindings,
                                                   DataFormatter formatter) {
        if (!bindings.isEmpty()) {
            Map<String, String> out = new LinkedHashMap<>();
            for (ColumnBinding binding : bindings) {
                int idx = indexOfHeader(headers, binding.source());
                if (idx < 0) {
                    out.put(binding.target(), null);
                    continue;
                }
                Cell cell = row.getCell(idx);
                out.put(binding.target(), cell == null ? null : formatter.formatCellValue(cell).trim());
            }
            return out;
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> defs = defaultHeaders();
        for (int i = 0; i < defs.size(); i++) {
            String field = defs.get(i);
            Cell cell = i < row.getLastCellNum() ? row.getCell(i) : null;
            out.put(field, cell == null ? null : formatter.formatCellValue(cell).trim());
        }
        return out;
    }

    private int indexOfHeader(List<String> headers, String source) {
        for (int i = 0; i < headers.size(); i++) {
            if (source != null && source.equalsIgnoreCase(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int resolveExcelSheetIndex(Object templateConfigObject) {
        Map<String, Object> hints = parseHints(templateConfigObject);
        Object v = hints.get("excelSheetIndex");
        if (v instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            return Math.max(0, Integer.parseInt(String.valueOf(v).trim()));
        }
        if (templateConfigObject instanceof Map<?, ?> map) {
            Object direct = map.get("excel_sheet_index");
            if (direct instanceof Number number) {
                return Math.max(0, number.intValue());
            }
        }
        return 0;
    }

    private long parseXmlPayloads(ImportJobContext context,
                                  String payloadText,
                                  Object templateConfigObject,
                                  BufferedWriter writer,
                                  boolean preserveLogicalRow) throws Exception {
        if (!StringUtils.hasText(payloadText)) {
            return 0L;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(payloadText)));
        String recordElement = resolveXmlRecordElement(templateConfigObject);
        NodeList nodes = doc.getElementsByTagNameNS("*", recordElement);
        if (nodes.getLength() == 0) {
            nodes = doc.getElementsByTagName(recordElement);
        }
        long recordNo = 0L;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            recordNo++;
                try {
                    Map<String, String> row = new LinkedHashMap<>();
                    NodeList children = element.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                        if (children.item(j) instanceof Element child) {
                            row.put(localElementName(child), child.getTextContent() == null ? null : child.getTextContent().trim());
                        }
                    }
                    collectSchemaFields(context, row);
                    writeParsedRecord(new WriteParsedRecordContext(context, writer, row, preserveLogicalRow, recordNo, "IMPORT_PARSE_XML_INVALID", row));
                } catch (Exception exception) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_XML_INVALID", exception.getMessage(), element);
                }
        }
        return recordNo;
    }

    private String resolveXmlRecordElement(Object templateConfigObject) {
        Map<String, Object> hints = parseHints(templateConfigObject);
        Object v = hints.get("xmlRecordElement");
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            return String.valueOf(v).trim();
        }
        if (templateConfigObject instanceof Map<?, ?> map) {
            Object direct = map.get("xml_record_element");
            if (direct != null && StringUtils.hasText(String.valueOf(direct))) {
                return String.valueOf(direct).trim();
            }
        }
        return "record";
    }

    private String localElementName(Element element) {
        String local = element.getLocalName();
        if (StringUtils.hasText(local)) {
            return local;
        }
        String tag = element.getTagName();
        if (tag != null && tag.contains(":")) {
            return tag.substring(tag.indexOf(':') + 1);
        }
        return tag;
    }

    private long parseFixedWidthPayloads(ImportJobContext context,
                                         String payloadText,
                                         ImportPayload importPayload,
                                         Object templateConfigObject,
                                         BufferedWriter writer,
                                         boolean preserveLogicalRow) throws Exception {
        if (!StringUtils.hasText(payloadText)) {
            return 0L;
        }
        int footerRows = resolveInt(importPayload == null ? null : importPayload.footerRows(), templateConfigObject, "footer_rows", 0);
        int headerRows = resolveInt(importPayload == null ? null : importPayload.headerRows(), templateConfigObject, "header_rows", 0);
        int recordLength = resolveInt(null, templateConfigObject, "record_length", 0);
        List<FixedWidthField> fields = loadFixedWidthFields(templateFieldMappings(templateConfigObject));
        if (fields.isEmpty()) {
            throw new IllegalStateException("FIXED_WIDTH requires field_mappings with start/length/target");
        }
        long recordNo = 0L;
        List<String> footerBuffer = footerRows > 0 ? new ArrayList<>(footerRows + 1) : List.of();
        try (BufferedReader reader = new BufferedReader(new StringReader(payloadText))) {
            String line;
            int nonBlankLineNo = 0;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                nonBlankLineNo++;
                if (headerRows > 0 && nonBlankLineNo <= headerRows) {
                    continue;
                }
                if (footerRows > 0) {
                    footerBuffer.add(line);
                    if (footerBuffer.size() <= footerRows) {
                        continue;
                    }
                    line = footerBuffer.remove(0);
                }
                recordNo++;
                if (recordLength > 0 && line.length() < recordLength) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_FIXED_INVALID", "line shorter than record_length", line);
                    continue;
                }
                try {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (FixedWidthField field : fields) {
                        if (field.start() + field.length() > line.length()) {
                            throw new IllegalStateException("field overflow: " + field.target());
                        }
                        String value = line.substring(field.start(), field.start() + field.length()).trim();
                        row.put(field.target(), value);
                    }
                    collectSchemaFields(context, row);
                    writeParsedRecord(new WriteParsedRecordContext(context, writer, row, preserveLogicalRow, recordNo, "IMPORT_PARSE_FIXED_INVALID", row));
                } catch (Exception exception) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_FIXED_INVALID", exception.getMessage(), line);
                }
            }
        }
        return recordNo;
    }

    private List<FixedWidthField> loadFixedWidthFields(Object fieldMappings) {
        if (!(fieldMappings instanceof List<?> list)) {
            return List.of();
        }
        List<FixedWidthField> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object target = map.get("target");
                Object start = map.get("start");
                Object length = map.get("length");
                if (target != null && start instanceof Number && length instanceof Number) {
                    out.add(new FixedWidthField(
                            String.valueOf(target),
                            ((Number) start).intValue(),
                            ((Number) length).intValue()
                    ));
                }
            }
        }
        return out;
    }

    private Object templateFieldMappings(Object templateConfigObject) {
        if (!(templateConfigObject instanceof Map<?, ?> map)) {
            return null;
        }
        Object fm = map.get("field_mappings");
        if (fm instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, new TypeReference<List<Object>>() {
                });
            } catch (Exception ignored) {
                return null;
            }
        }
        return fm;
    }

    private Map<String, Object> parseHints(Object templateConfigObject) {
        if (!(templateConfigObject instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> schemaMap = readJsonObject(map.get("query_param_schema"));
        Object hints = schemaMap.get("parseHints");
        if (!(hints instanceof Map<?, ?> hintMap)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        hintMap.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private Map<String, Object> readJsonObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private List<ColumnBinding> loadColumnBindings(Object fieldMappings) {
        if (!(fieldMappings instanceof List<?> list)) {
            return List.of();
        }
        List<ColumnBinding> bindings = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object source = map.get("source");
                Object target = map.get("target");
                if (source != null && target != null) {
                    bindings.add(new ColumnBinding(String.valueOf(source), String.valueOf(target)));
                }
            }
        }
        return bindings;
    }

    private record ColumnBinding(String source, String target) {
    }

    private record FixedWidthField(String target, int start, int length) {
    }

    private long parseJsonPayloads(ImportJobContext context, String payloadText, BufferedWriter writer, boolean preserveLogicalRow) throws Exception {
        if (!StringUtils.hasText(payloadText)) {
            return 0L;
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(payloadText)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                return 0L;
            }
            if (token == JsonToken.START_ARRAY) {
                return parseJsonArray(context, parser, writer, preserveLogicalRow);
            }
            if (token == JsonToken.START_OBJECT) {
                // Envelope path: {"records":[...]} or single-record object.
                // If records array is absent, treat the root object as one logical record.
                JsonNode rootObj = objectMapper.readTree(parser);
                JsonNode recordsNode = rootObj == null ? null : rootObj.get("records");
                if (recordsNode != null && recordsNode.isArray()) {
                    long recordNo = 0L;
                    for (JsonNode record : recordsNode) {
                        if (record == null || record.isNull()) {
                            continue;
                        }
                        recordNo++;
                        collectSchemaFields(context, record);
                        writeJsonRecord(context, writer, record, recordNo, preserveLogicalRow);
                    }
                    return recordNo;
                }

                if (rootObj == null || rootObj.isNull()) {
                    return 0L;
                }
                collectSchemaFields(context, rootObj);
                writeJsonRecord(context, writer, rootObj, 1L, preserveLogicalRow);
                return 1L;
            }
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || node.isNull()) {
                return 0L;
            }
            collectSchemaFields(context, node);
            writeJsonRecord(context, writer, node, 1L, preserveLogicalRow);
            return 1L;
        }
    }

    /**
     * Streams a JSON object payload without loading it fully into memory.
     * Handles {@code {"records":[...]}} envelope by navigating to the "records" array
     * via the streaming parser. For any other top-level object, reads it as a single record.
     */
    private long parseJsonObjectStreaming(ImportJobContext context,
                                          JsonParser parser,
                                          BufferedWriter writer,
                                          boolean preserveLogicalRow) throws Exception {
        // Navigate fields in the root object looking for "records" array
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() == JsonToken.FIELD_NAME && "records".equals(parser.currentName())) {
                JsonToken arrayToken = parser.nextToken();
                if (arrayToken == JsonToken.START_ARRAY) {
                    return parseJsonArray(context, parser, writer, preserveLogicalRow);
                }
                // "records" field is not an array — read it as a single value and fall through
                parser.skipChildren();
            } else {
                // Skip non-records fields
                parser.skipChildren();
            }
        }
        // No "records" array found — this is a single-object payload; re-read is not possible
        // since we consumed the parser. Return 0 to signal no records. Callers that need a
        // single-record fallback should send a JSON array instead.
        return 0L;
    }

    private long parseJsonArray(ImportJobContext context, JsonParser parser, BufferedWriter writer, boolean preserveLogicalRow) throws Exception {
        long recordNo = 0L;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || node.isNull()) {
                continue;
            }
            recordNo++;
            collectSchemaFields(context, node);
            writeJsonRecord(context, writer, node, recordNo, preserveLogicalRow);
        }
        return recordNo;
    }

    private void writeJsonRecord(ImportJobContext context,
                                 BufferedWriter writer,
                                 JsonNode node,
                                 long recordNo,
                                 boolean preserveLogicalRow) throws Exception {
        try {
            Map<String, Object> row = objectMapper.convertValue(node, MAP_TYPE);
            writeParsedRecord(new WriteParsedRecordContext(context, writer, row, preserveLogicalRow, recordNo, "IMPORT_PARSE_JSON_INVALID", node));
        } catch (Exception exception) {
            recordParseError(context, recordNo, "IMPORT_PARSE_JSON_INVALID", exception.getMessage(), node);
        }
    }

    private long parseDelimitedPayloads(ImportJobContext context,
                                        String payloadText,
                                        ImportPayload importPayload,
                                        Object templateConfigObject,
                                        BufferedWriter writer,
                                        boolean preserveLogicalRow) throws Exception {
        if (!StringUtils.hasText(payloadText)) {
            return 0L;
        }
        int footerRows = resolveInt(importPayload == null ? null : importPayload.footerRows(), templateConfigObject, "footer_rows", 0);
        int headerRows = resolveInt(importPayload == null ? null : importPayload.headerRows(), templateConfigObject, "header_rows", 0);
        boolean withHeader = importPayload != null && importPayload.withHeader() != null
                ? importPayload.withHeader()
                : headerRows > 0;
        String delimiter = resolveDelimiter(importPayload, templateConfigObject);
        List<String> headers = defaultHeaders();
        long recordNo = 0L;
        long parsedCount = 0L;
        int dataLineIndex = 0;
        List<String> footerBuffer = footerRows > 0 ? new ArrayList<>(footerRows + 1) : List.of();
        try (BufferedReader reader = new BufferedReader(new StringReader(payloadText))) {
            String line;
            int nonBlankLineNo = 0;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                nonBlankLineNo++;
                if ((withHeader || headerRows > 0) && nonBlankLineNo <= Math.max(headerRows, 1)) {
                    if (nonBlankLineNo == 1) {
                        headers = parseDelimitedLine(line, delimiter);
                        context.getAttributes().put("schemaFields", new ArrayList<>(headers));
                    }
                    continue;
                }
                if (footerRows > 0) {
                    footerBuffer.add(line);
                    if (footerBuffer.size() <= footerRows) {
                        continue;
                    }
                    line = footerBuffer.remove(0);
                }
                recordNo++;
                try {
                    List<String> columns = parseDelimitedLine(line, delimiter);
                    if (columns.isEmpty()) {
                        recordParseError(context, recordNo, "IMPORT_PARSE_LINE_INVALID", "empty line", line);
                        continue;
                    }
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        row.put(headers.get(i), i < columns.size() ? columns.get(i) : null);
                    }
                    collectSchemaFields(context, row);
                    writeParsedRecord(new WriteParsedRecordContext(context, writer, row, preserveLogicalRow, recordNo, "IMPORT_PARSE_LINE_INVALID", row));
                    parsedCount = numberValue(context.getAttributes().get("parsedCount"));
                } catch (Exception exception) {
                    recordParseError(context, recordNo, "IMPORT_PARSE_LINE_INVALID", exception.getMessage(), line);
                }
            }
        }
        return recordNo;
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
        if (StringUtils.hasText(payloadText)) {
            String trim = payloadText.trim();
            if (trim.startsWith("{") || trim.startsWith("[")) {
                return "JSON";
            }
        }
        return "DELIMITED";
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

    private void collectSchemaFields(ImportJobContext context, Map<String, ?> row) {
        if (context == null || row == null || row.isEmpty()) {
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
        row.keySet().forEach(fields::add);
        context.getAttributes().put("schemaFields", new ArrayList<>(fields));
    }

    private record WriteParsedRecordContext(ImportJobContext context, BufferedWriter writer,
                                            Map<String, ?> row, boolean preserveLogicalRow,
                                            long recordNo, String errorCode, Object rawRecord) {}

    private void writeParsedRecord(WriteParsedRecordContext ctx) throws Exception {
        ImportJobContext context = ctx.context();
        BufferedWriter writer = ctx.writer();
        Map<String, ?> row = ctx.row();
        boolean preserveLogicalRow = ctx.preserveLogicalRow();
        long recordNo = ctx.recordNo();
        String errorCode = ctx.errorCode();
        Object rawRecord = ctx.rawRecord();
        if (row == null || row.isEmpty()) {
            recordParseError(context, recordNo, errorCode, "parsed row is empty", rawRecord);
            return;
        }
        Object value = row;
        if (!preserveLogicalRow) {
            try {
                CustomerImportPayload payload = objectMapper.convertValue(row, CustomerImportPayload.class);
                if (payload == null) {
                    recordParseError(context, recordNo, errorCode, "record cannot convert to payload", rawRecord);
                    return;
                }
                value = payload;
            } catch (RuntimeException ex) {
                // Some JSON payloads may carry additional fields in the parsed row map.
                // CustomerImportPayload only models mapped biz columns; ignore unknown fields.
                if (row == null) {
                    throw ex;
                }
                ObjectMapper lenient = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                CustomerImportPayload payload = lenient.convertValue(row, CustomerImportPayload.class);
                if (payload == null) {
                    recordParseError(context, recordNo, errorCode, "record cannot convert to payload", rawRecord);
                    return;
                }
                value = payload;
            }
        }
        writeNdjsonValue(writer, value);
        writer.newLine();
        context.getAttributes().put("parsedCount", numberValue(context.getAttributes().get("parsedCount")) + 1);
    }

    /**
     * Writes one JSON value without closing the underlying writer (NDJSON writes many lines per staging file).
     */
    private void writeNdjsonValue(Writer writer, Object value) throws IOException {
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(writer)) {
            generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
            objectMapper.writeValue(generator, value);
        }
    }

    private boolean preserveLogicalRow(ImportJobContext context, Object templateConfigObject) {
        if (!(templateConfigObject instanceof Map<?, ?> templateConfig)) {
            return false;
        }
        // Template config from MyBatis may use different key styles (snake_case vs camelCase).
        Object direct = templateConfig.get("load_target_ref");
        if (direct == null) {
            direct = templateConfig.get("loadTargetRef");
        }
        if (direct != null && WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED.equalsIgnoreCase(String.valueOf(direct).trim())) {
            return true;
        }
        if (templateConfig.get("jdbc_mapped_import") != null || templateConfig.get("jdbcMappedImport") != null) {
            return true;
        }
        Map<String, Object> querySchema = readJsonObject(templateConfig.get("query_param_schema"));
        return querySchema.get("jdbcMappedImport") instanceof Map<?, ?>;
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

    private Path createStagingFile(ImportJobContext context, String phase) throws Exception {
        String fileId = context == null ? "unknown" : String.valueOf(context.getFileId());
        String workerId = context == null ? "worker" : String.valueOf(context.getWorkerId());
        return Files.createTempFile(BatchFileConstants.importStagePrefix(fileId, workerId, phase), BatchFileConstants.NDJSON_SUFFIX);
    }
}
