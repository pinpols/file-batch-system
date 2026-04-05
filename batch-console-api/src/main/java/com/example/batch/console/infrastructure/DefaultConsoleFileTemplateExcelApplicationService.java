package com.example.batch.console.infrastructure;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.param.FileTemplateConfigUpsertParam;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.FileTemplateExcelImportStore;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link com.example.batch.console.application.ConsoleFileTemplateExcelApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleFileTemplateExcelApplicationService implements ConsoleFileTemplateExcelApplicationService {

    private static final String SHEET_NAME = "file_template_config";
    private static final List<String> COLUMNS = List.of(
            "tenant_id",
            "template_code",
            "template_name",
            "template_type",
            "biz_type",
            "file_format_type",
            "charset",
            "target_charset",
            "with_bom",
            "line_separator",
            "delimiter",
            "quote_char",
            "escape_char",
            "record_length",
            "header_rows",
            "footer_rows",
            "header_template",
            "trailer_template",
            "checksum_type",
            "compress_type",
            "encrypt_type",
            "naming_rule",
            "field_mappings",
            "validation_rule_set",
            "default_query_code",
            "default_query_sql",
            "query_param_schema",
            "streaming_enabled",
            "page_size",
            "fetch_size",
            "chunk_size",
            "preview_masking_enabled",
            "error_line_masking_enabled",
            "log_masking_enabled",
            "content_encryption_enabled",
            "encryption_key_ref",
            "download_requires_approval",
            "masking_rule_set",
            "enabled",
            "version",
            "description"
    );
    private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
    private static final Set<String> FILE_FORMAT_TYPES = Set.of("DELIMITED", "FIXED_WIDTH", "EXCEL", "XML", "JSON", "BINARY");
    private static final Set<String> TEMPLATE_TYPES = Set.of("IMPORT", "EXPORT", "SHARED");
    private static final Set<String> CHECKSUM_TYPES = Set.of("NONE", "MD5", "SHA-256");
    private static final Set<String> COMPRESS_TYPES = Set.of("NONE", "ZIP", "GZIP");
    private static final Set<String> ENCRYPT_TYPES = Set.of("NONE", "AES", "PGP", "CUSTOM");
    private static final int[] BOOLEAN_VALIDATION_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final FileTemplateExcelImportStore importStore;

    @Override
    public ResponseEntity<InputStreamResource> exportFileTemplates(FileTemplateQueryRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        List<Map<String, Object>> rows = fileTemplateConfigMapper.selectByQuery(
                tenantId,
                request.getKeyword(),
                request.getTemplateCode(),
                request.getTemplateName(),
                request.getTemplateType(),
                request.getBizType(),
                request.getEnabled(),
                null
        );
        byte[] workbookBytes = writeWorkbook(rows);
        InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
        String fileName = "file-template-config-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Override
    public ConsoleFileTemplateExcelUploadResponse upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file is required");
        }
        String tenantId = tenantGuard.resolveTenant(null);
        ParsedWorkbook parsedWorkbook = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
        String uploadToken = importStore.save(parsedWorkbook.fileName(), parsedWorkbook.tenantId(), parsedWorkbook.sheetName(), parsedWorkbook.rows());
        return new ConsoleFileTemplateExcelUploadResponse(uploadToken, parsedWorkbook.fileName(), parsedWorkbook.sheetName(), parsedWorkbook.rows().size());
    }

    @Override
    public ConsoleFileTemplateExcelPreviewResponse preview(String uploadToken) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        return new ConsoleFileTemplateExcelPreviewResponse(
                uploadToken,
                session.fileName(),
                session.sheetName(),
                validationResult.totalRows(),
                validationResult.validRows(),
                validationResult.invalidRows(),
                validationResult.rows().stream().map(this::toResponse).toList(),
                validationResult.issues()
        );
    }

    @Override
    @Transactional
    public ConsoleFileTemplateExcelApplyResponse apply(String uploadToken, FileTemplateExcelApplyRequest request) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        if (validationResult.invalidRows() > 0) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid template rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        String operatorId = metadata.operatorId();
        int inserted = 0;
        int updated = 0;
        for (TemplateRow row : validationResult.rows()) {
            Map<String, Object> existing = fileTemplateConfigMapper.selectByUniqueKey(session.tenantId(), row.templateCode(), row.version());
            fileTemplateConfigMapper.upsertFileTemplateConfig(toUpsertParam(session.tenantId(), row, operatorId));
            if (existing == null || existing.isEmpty()) {
                inserted++;
            } else {
                updated++;
            }
            logChange(session.tenantId(), row, request.getReason(), operatorId, metadata.traceId(), existing == null || existing.isEmpty() ? "EXCEL_INSERT" : "EXCEL_UPDATE");
        }
        importStore.remove(uploadToken);
        return new ConsoleFileTemplateExcelApplyResponse(uploadToken, session.tenantId(), validationResult.totalRows(), inserted, updated);
    }

    private ParsedSession loadSession(String uploadToken) {
        if (!StringUtils.hasText(uploadToken)) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "uploadToken is required");
        }
        FileTemplateExcelImportStore.ExcelImportSession session = importStore.get(uploadToken);
        if (session == null) {
            throw new BizException(ResultCode.NOT_FOUND, "excel upload session not found");
        }
        tenantGuard.assertTenantAllowed(session.tenantId());
        return new ParsedSession(session.fileName(), session.tenantId(), session.sheetName(), session.uploadedAt(), session.rows());
    }

    private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = sheet.getSheetName();
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, "excel header row is missing");
            }
            Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
            validateHeaders(headerIndex);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || rowIsBlank(row, formatter)) {
                    continue;
                }
                Map<String, String> rowValues = new LinkedHashMap<>();
                for (String header : COLUMNS) {
                    Integer columnIndex = headerIndex.get(header);
                    rowValues.put(header, normalize(cellText(row, columnIndex, formatter)));
                }
                rowValues.put("tenant_id", StringUtils.hasText(rowValues.get("tenant_id")) ? rowValues.get("tenant_id") : tenantId);
                rows.add(rowValues);
            }
            return new ParsedWorkbook(fileNameOrDefault(originalFileName), tenantId, sheetName, rows);
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + exception.getMessage());
        }
    }

    private ValidationResult validateRows(ParsedSession session) {
        List<TemplateRow> rows = new ArrayList<>();
        List<ConsoleExcelRowIssueResponse> issues = new ArrayList<>();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> rowValues : session.rows()) {
            List<String> rowIssues = new ArrayList<>();
            TemplateRow row = toTemplateRow(session.tenantId(), rowNo, rowValues, rowIssues);
            String uniqueKey = row.templateCode() + "#" + row.version();
            if (!uniqueKeys.add(uniqueKey)) {
                rowIssues.add("duplicate template code/version in excel: " + uniqueKey);
            }
            if (rowIssues.isEmpty()) {
                rows.add(row);
            } else {
                issues.add(new ConsoleExcelRowIssueResponse(rowNo, uniqueKey, row.templateCode(), row.version(), List.copyOf(rowIssues)));
            }
            rowNo++;
        }
        int totalRows = session.rows().size();
        return new ValidationResult(totalRows, rows.size(), totalRows - rows.size(), rows, issues);
    }

    private TemplateRow toTemplateRow(String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
        String effectiveTenant = normalize(values.get("tenant_id"));
        if (!StringUtils.hasText(effectiveTenant)) {
            effectiveTenant = tenantId;
        } else if (!tenantId.equals(effectiveTenant)) {
            issues.add("tenant_id must match current tenant: " + tenantId);
        }
        String templateCode = requireText(values, "template_code", 128, issues);
        String templateName = requireText(values, "template_name", 256, issues);
        String templateType = requireEnum(values, "template_type", TEMPLATE_TYPES, 32, issues);
        String bizType = optionalText(values, "biz_type", 64, issues);
        String fileFormatType = requireEnum(values, "file_format_type", FILE_FORMAT_TYPES, 32, issues);
        String charset = optionalText(values, "charset", 32, issues);
        String targetCharset = optionalText(values, "target_charset", 32, issues);
        Boolean withBom = optionalBoolean(values, "with_bom", false, issues);
        String lineSeparator = optionalText(values, "line_separator", 16, issues);
        String delimiter = optionalText(values, "delimiter", 8, issues);
        String quoteChar = optionalText(values, "quote_char", 8, issues);
        String escapeChar = optionalText(values, "escape_char", 8, issues);
        Integer recordLength = optionalInteger(values, "record_length", 0, issues);
        Integer headerRows = optionalInteger(values, "header_rows", 0, issues);
        Integer footerRows = optionalInteger(values, "footer_rows", 0, issues);
        String headerTemplateJson = optionalJson(values, "header_template", issues);
        String trailerTemplateJson = optionalJson(values, "trailer_template", issues);
        String checksumType = requireEnum(values, "checksum_type", CHECKSUM_TYPES, 32, issues);
        String compressType = requireEnum(values, "compress_type", COMPRESS_TYPES, 32, issues);
        String encryptType = requireEnum(values, "encrypt_type", ENCRYPT_TYPES, 32, issues);
        String namingRule = optionalText(values, "naming_rule", 512, issues);
        String fieldMappingsJson = optionalJson(values, "field_mappings", issues);
        String validationRuleSetJson = optionalJson(values, "validation_rule_set", issues);
        String defaultQueryCode = optionalText(values, "default_query_code", 128, issues);
        String defaultQuerySql = optionalText(values, "default_query_sql", 10000, issues);
        String queryParamSchemaJson = optionalJson(values, "query_param_schema", issues);
        Boolean streamingEnabled = optionalBoolean(values, "streaming_enabled", true, issues);
        Integer pageSize = optionalInteger(values, "page_size", 1000, issues);
        Integer fetchSize = optionalInteger(values, "fetch_size", 1000, issues);
        Integer chunkSize = optionalInteger(values, "chunk_size", 500, issues);
        Boolean previewMaskingEnabled = optionalBoolean(values, "preview_masking_enabled", false, issues);
        Boolean errorLineMaskingEnabled = optionalBoolean(values, "error_line_masking_enabled", false, issues);
        Boolean logMaskingEnabled = optionalBoolean(values, "log_masking_enabled", false, issues);
        Boolean contentEncryptionEnabled = optionalBoolean(values, "content_encryption_enabled", false, issues);
        String encryptionKeyRef = optionalText(values, "encryption_key_ref", 128, issues);
        Boolean downloadRequiresApproval = optionalBoolean(values, "download_requires_approval", false, issues);
        String maskingRuleSet = optionalText(values, "masking_rule_set", 256, issues);
        Boolean enabled = optionalBoolean(values, "enabled", true, issues);
        Integer version = optionalInteger(values, "version", 1, issues);
        String description = optionalText(values, "description", 1024, issues);
        return TemplateRow.builder()
                .rowNo(rowNo)
                .tenantId(effectiveTenant)
                .templateCode(templateCode)
                .templateName(templateName)
                .templateType(templateType)
                .bizType(bizType)
                .fileFormatType(fileFormatType)
                .charset(charset)
                .targetCharset(targetCharset)
                .withBom(withBom)
                .lineSeparator(lineSeparator)
                .delimiter(delimiter)
                .quoteChar(quoteChar)
                .escapeChar(escapeChar)
                .recordLength(recordLength)
                .headerRows(headerRows)
                .footerRows(footerRows)
                .headerTemplateJson(headerTemplateJson)
                .trailerTemplateJson(trailerTemplateJson)
                .checksumType(checksumType)
                .compressType(compressType)
                .encryptType(encryptType)
                .namingRule(namingRule)
                .fieldMappingsJson(fieldMappingsJson)
                .validationRuleSetJson(validationRuleSetJson)
                .defaultQueryCode(defaultQueryCode)
                .defaultQuerySql(defaultQuerySql)
                .queryParamSchemaJson(queryParamSchemaJson)
                .streamingEnabled(streamingEnabled)
                .pageSize(pageSize)
                .fetchSize(fetchSize)
                .chunkSize(chunkSize)
                .previewMaskingEnabled(previewMaskingEnabled)
                .errorLineMaskingEnabled(errorLineMaskingEnabled)
                .logMaskingEnabled(logMaskingEnabled)
                .contentEncryptionEnabled(contentEncryptionEnabled)
                .encryptionKeyRef(encryptionKeyRef)
                .downloadRequiresApproval(downloadRequiresApproval)
                .maskingRuleSet(maskingRuleSet)
                .enabled(enabled)
                .version(version)
                .description(description)
                .build();
    }

    private String requireText(Map<String, String> values, String key, int maxLength, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            issues.add(key + " is required");
            return null;
        }
        if (normalized.length() > maxLength) {
            issues.add(key + " too long (max " + maxLength + ")");
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private String optionalText(Map<String, String> values, String key, int maxLength, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (normalized.length() > maxLength) {
            issues.add(key + " too long (max " + maxLength + ")");
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private String requireEnum(Map<String, String> values, String key, Set<String> allowed, int maxLength, List<String> issues) {
        String normalized = requireText(values, key, maxLength, issues);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            issues.add(key + " must be one of " + allowed);
            return normalized;
        }
        return upper;
    }

    private Boolean optionalBoolean(Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized) || "yes".equalsIgnoreCase(normalized) || "y".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized) || "no".equalsIgnoreCase(normalized) || "n".equalsIgnoreCase(normalized)) {
            return false;
        }
        issues.add(key + " must be boolean");
        return defaultValue;
    }

    private Integer optionalInteger(Map<String, String> values, String key, Integer defaultValue, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException exception) {
            issues.add(key + " must be integer");
            return defaultValue;
        }
    }

    private String optionalJson(Map<String, String> values, String key, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            JsonUtils.fromJson(normalized, Object.class);
            return normalized;
        } catch (IllegalArgumentException exception) {
            issues.add(key + " must be valid JSON");
            return normalized;
        }
    }

    private String normalize(String value) {
        return ConsoleTextSanitizer.normalize(value);
    }

    private Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> headers = new LinkedHashMap<>();
        for (int cellIndex = headerRow.getFirstCellNum(); cellIndex < headerRow.getLastCellNum(); cellIndex++) {
            Cell cell = headerRow.getCell(cellIndex);
            String header = normalize(formatter.formatCellValue(cell));
            if (StringUtils.hasText(header)) {
                headers.put(header, cellIndex);
            }
        }
        return headers;
    }

    private void validateHeaders(Map<String, Integer> headerIndex) {
        Set<String> missing = new LinkedHashSet<>(REQUIRED_HEADERS);
        missing.removeAll(headerIndex.keySet());
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel header missing: " + String.join(", ", missing));
        }
    }

    private boolean rowIsBlank(Row row, DataFormatter formatter) {
        for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
            String value = normalize(formatter.formatCellValue(row.getCell(cellIndex)));
            if (StringUtils.hasText(value)) {
                return false;
            }
        }
        return true;
    }

    private String cellText(Row row, Integer columnIndex, DataFormatter formatter) {
        if (columnIndex == null) {
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        return cell == null ? null : formatter.formatCellValue(cell);
    }

    private byte[] writeWorkbook(List<Map<String, Object>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(50); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet dataSheet = workbook.createSheet(SHEET_NAME);
            dataSheet.createFreezePane(0, 1);
            CellStyle headerStyle = createHeaderStyle(workbook);
            Row headerRow = dataSheet.createRow(0);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Cell headerCell = headerRow.createCell(i);
                headerCell.setCellValue(COLUMNS.get(i));
                headerCell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                Row dataRow = dataSheet.createRow(rowIndex++);
                for (int i = 0; i < COLUMNS.size(); i++) {
                    String header = COLUMNS.get(i);
                    Cell cell = dataRow.createCell(i);
                    Object value = row.get(header);
                    if (value == null) {
                        cell.setCellValue("");
                    } else {
                        cell.setCellValue(String.valueOf(value));
                    }
                }
            }
            applyValidations(dataSheet);
            for (int i = 0; i < COLUMNS.size(); i++) {
                dataSheet.setColumnWidth(i, Math.min(12000, Math.max(18, COLUMNS.get(i).length() + 4) * 256));
            }
            createReadmeSheet(workbook);
            createDictSheet(workbook);
            createValidationSheet(workbook);
            workbook.write(out);
            workbook.dispose();
            return out.toByteArray();
        } catch (IOException exception) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
        }
    }

    private void applyValidations(Sheet sheet) {
        addListValidation(sheet, 3, TEMPLATE_TYPES.toArray(String[]::new));
        addListValidation(sheet, 5, FILE_FORMAT_TYPES.toArray(String[]::new));
        addListValidation(sheet, 18, CHECKSUM_TYPES.toArray(String[]::new));
        addListValidation(sheet, 19, COMPRESS_TYPES.toArray(String[]::new));
        addListValidation(sheet, 20, ENCRYPT_TYPES.toArray(String[]::new));
        addBooleanValidation(sheet, BOOLEAN_VALIDATION_COLUMNS);
    }

    private void addListValidation(Sheet sheet, int columnIndex, String[] values) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
        CellRangeAddressList addressList = new CellRangeAddressList(1, 5000, columnIndex, columnIndex);
        DataValidation validation = helper.createValidation(constraint, addressList);
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private void addBooleanValidation(Sheet sheet, int... columns) {
        for (int columnIndex : columns) {
            addListValidation(sheet, columnIndex, new String[]{"TRUE", "FALSE"});
        }
    }

    private void createReadmeSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("README");
        sheet.setColumnWidth(0, 16000);
        String[] lines = {
                "file template config 维护模板",
                "1. 主数据页必须在第一个 sheet。",
                "2. 导出结果可以直接修改后再导入。",
                "3. 必填列、枚举列、布尔列已经内置数据校验。",
                "4. 只读字段不要改，preview 会进一步校验。",
                "5. JSON 类字段请保持合法 JSON。",
                "6. 导入流程必须先 upload，再 preview，最后 apply。"
        };
        for (int i = 0; i < lines.length; i++) {
            Row row = sheet.createRow(i);
            row.createCell(0).setCellValue(lines[i]);
        }
    }

    private void createDictSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("DICT");
        sheet.createFreezePane(0, 1);
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("field");
        header.createCell(1).setCellValue("value");
        header.createCell(2).setCellValue("description");
        String[][] rows = {
                {"template_type", "IMPORT", "import template"},
                {"template_type", "EXPORT", "export template"},
                {"template_type", "SHARED", "shared template"},
                {"file_format_type", "DELIMITED", "delimited text"},
                {"file_format_type", "FIXED_WIDTH", "fixed width text"},
                {"file_format_type", "EXCEL", "excel workbook"},
                {"file_format_type", "XML", "xml payload"},
                {"file_format_type", "JSON", "json payload"},
                {"file_format_type", "BINARY", "binary payload"},
                {"checksum_type", "NONE", "no checksum"},
                {"checksum_type", "MD5", "md5 checksum"},
                {"checksum_type", "SHA-256", "sha-256 checksum"},
                {"compress_type", "NONE", "no compression"},
                {"compress_type", "ZIP", "zip compression"},
                {"compress_type", "GZIP", "gzip compression"},
                {"encrypt_type", "NONE", "no encryption"},
                {"encrypt_type", "AES", "aes encryption"},
                {"encrypt_type", "PGP", "pgp encryption"},
                {"encrypt_type", "CUSTOM", "custom encryption"},
                {"enabled", "TRUE", "enabled"},
                {"enabled", "FALSE", "disabled"},
                {"with_bom", "TRUE", "with bom"},
                {"with_bom", "FALSE", "without bom"},
                {"streaming_enabled", "TRUE", "streaming"},
                {"streaming_enabled", "FALSE", "non-streaming"},
                {"download_requires_approval", "TRUE", "requires approval"},
                {"download_requires_approval", "FALSE", "no approval"}
        };
        for (int i = 0; i < rows.length; i++) {
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(rows[i][0]);
            row.createCell(1).setCellValue(rows[i][1]);
            row.createCell(2).setCellValue(rows[i][2]);
        }
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        sheet.setColumnWidth(2, 36 * 256);
    }

    private void createValidationSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("VALIDATION");
        sheet.createFreezePane(0, 1);
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("sheet_name");
        header.createCell(1).setCellValue("row_no");
        header.createCell(2).setCellValue("column_name");
        header.createCell(3).setCellValue("error_reason");
        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 28 * 256);
        sheet.setColumnWidth(3, 50 * 256);
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor((short) 22);
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        Font font = workbook.createFont();
        font.setBold(true);
        headerStyle.setFont(font);
        return headerStyle;
    }

    private void logChange(String tenantId, TemplateRow row, String reason, String operatorId, String traceId, String action) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", tenantId,
                "configType", "FILE_TEMPLATE",
                "configKey", row.templateCode(),
                "versionNo", row.version(),
                "changeAction", action,
                "changeResult", "SUCCESS",
                "operatorType", "USER",
                "operatorId", ConsoleTextSanitizer.safeInput(operatorId, 64),
                "traceId", ConsoleTextSanitizer.safeInput(traceId, 128),
                "changeSummaryJson", JsonUtils.toJson(mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail", mapOf(
                                "templateName", row.templateName(),
                                "enabled", row.enabled(),
                                "fileFormatType", row.fileFormatType()
                        )
                ))
        ));
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private String fileNameOrDefault(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "file-template-config.xlsx";
        }
        String normalized = ConsoleTextSanitizer.normalize(fileName);
        if (!StringUtils.hasText(normalized)) {
            return "file-template-config.xlsx";
        }
        return normalized.endsWith(".xlsx") ? normalized : normalized + ".xlsx";
    }

    private ConsoleFileTemplateResponse toResponse(TemplateRow row) {
        return new ConsoleFileTemplateResponse(
                null,
                row.tenantId(),
                row.templateCode(),
                row.templateName(),
                row.templateType(),
                row.bizType(),
                row.fileFormatType(),
                row.charset(),
                row.targetCharset(),
                row.withBom(),
                row.lineSeparator(),
                row.delimiter(),
                row.quoteChar(),
                row.escapeChar(),
                row.recordLength(),
                row.headerRows(),
                row.footerRows(),
                row.headerTemplateJson(),
                row.trailerTemplateJson(),
                row.checksumType(),
                row.compressType(),
                row.encryptType(),
                row.namingRule(),
                row.fieldMappingsJson(),
                row.validationRuleSetJson(),
                row.defaultQueryCode(),
                row.defaultQuerySql(),
                row.queryParamSchemaJson(),
                row.streamingEnabled(),
                row.pageSize(),
                row.fetchSize(),
                row.chunkSize(),
                row.previewMaskingEnabled(),
                row.errorLineMaskingEnabled(),
                row.logMaskingEnabled(),
                row.contentEncryptionEnabled(),
                row.encryptionKeyRef(),
                row.downloadRequiresApproval(),
                row.maskingRuleSet(),
                row.enabled(),
                row.version(),
                row.description(),
                null,
                null,
                null,
                null
        );
    }

    private FileTemplateConfigUpsertParam toUpsertParam(String tenantId, TemplateRow row, String operatorId) {
        FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
        param.setTenantId(tenantId);
        param.setTemplateCode(row.templateCode());

        FileTemplateConfigUpsertParam.BasicInfo basicInfo = new FileTemplateConfigUpsertParam.BasicInfo();
        basicInfo.setTemplateName(row.templateName());
        basicInfo.setTemplateType(row.templateType());
        basicInfo.setBizType(row.bizType());
        basicInfo.setEnabled(row.enabled());
        basicInfo.setVersion(row.version());
        basicInfo.setDescription(row.description());
        param.setBasicInfo(basicInfo);

        FileTemplateConfigUpsertParam.FormatOptions format = new FileTemplateConfigUpsertParam.FormatOptions();
        format.setFileFormatType(row.fileFormatType());
        format.setCharset(row.charset());
        format.setTargetCharset(row.targetCharset());
        format.setWithBom(row.withBom());
        format.setLineSeparator(row.lineSeparator());
        format.setDelimiter(row.delimiter());
        format.setQuoteChar(row.quoteChar());
        format.setEscapeChar(row.escapeChar());
        format.setRecordLength(row.recordLength());
        format.setHeaderRows(row.headerRows());
        format.setFooterRows(row.footerRows());
        format.setHeaderTemplateJson(row.headerTemplateJson());
        format.setTrailerTemplateJson(row.trailerTemplateJson());
        format.setChecksumType(row.checksumType());
        format.setCompressType(row.compressType());
        format.setEncryptType(row.encryptType());
        format.setNamingRule(row.namingRule());
        format.setFieldMappingsJson(row.fieldMappingsJson());
        format.setValidationRuleSetJson(row.validationRuleSetJson());
        param.setFormat(format);

        FileTemplateConfigUpsertParam.QueryOptions query = new FileTemplateConfigUpsertParam.QueryOptions();
        query.setDefaultQueryCode(row.defaultQueryCode());
        query.setDefaultQuerySql(row.defaultQuerySql());
        query.setQueryParamSchemaJson(row.queryParamSchemaJson());
        param.setQuery(query);

        FileTemplateConfigUpsertParam.RuntimeOptions runtime = new FileTemplateConfigUpsertParam.RuntimeOptions();
        runtime.setStreamingEnabled(row.streamingEnabled());
        runtime.setPageSize(row.pageSize());
        runtime.setFetchSize(row.fetchSize());
        runtime.setChunkSize(row.chunkSize());
        param.setRuntime(runtime);

        FileTemplateConfigUpsertParam.SecurityOptions security = new FileTemplateConfigUpsertParam.SecurityOptions();
        security.setPreviewMaskingEnabled(row.previewMaskingEnabled());
        security.setErrorLineMaskingEnabled(row.errorLineMaskingEnabled());
        security.setLogMaskingEnabled(row.logMaskingEnabled());
        security.setContentEncryptionEnabled(row.contentEncryptionEnabled());
        security.setEncryptionKeyRef(row.encryptionKeyRef());
        security.setDownloadRequiresApproval(row.downloadRequiresApproval());
        security.setMaskingRuleSet(row.maskingRuleSet());
        param.setSecurity(security);

        FileTemplateConfigUpsertParam.AuditOptions audit = new FileTemplateConfigUpsertParam.AuditOptions();
        audit.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
        audit.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
        param.setAudit(audit);
        return param;
    }

    private record ParsedWorkbook(String fileName, String tenantId, String sheetName, List<Map<String, String>> rows) {
    }

    private record ParsedSession(String fileName, String tenantId, String sheetName, Instant uploadedAt, List<Map<String, String>> rows) {
    }

    private record ValidationResult(int totalRows,
                                    int validRows,
                                    int invalidRows,
                                    List<TemplateRow> rows,
                                    List<ConsoleExcelRowIssueResponse> issues) {
    }

    @Getter
    @Builder
    @Accessors(fluent = true)
    private static class TemplateRow {
        private final Integer rowNo;
        private final String tenantId;
        private final String templateCode;
        private final String templateName;
        private final String templateType;
        private final String bizType;
        private final String fileFormatType;
        private final String charset;
        private final String targetCharset;
        private final Boolean withBom;
        private final String lineSeparator;
        private final String delimiter;
        private final String quoteChar;
        private final String escapeChar;
        private final Integer recordLength;
        private final Integer headerRows;
        private final Integer footerRows;
        private final String headerTemplateJson;
        private final String trailerTemplateJson;
        private final String checksumType;
        private final String compressType;
        private final String encryptType;
        private final String namingRule;
        private final String fieldMappingsJson;
        private final String validationRuleSetJson;
        private final String defaultQueryCode;
        private final String defaultQuerySql;
        private final String queryParamSchemaJson;
        private final Boolean streamingEnabled;
        private final Integer pageSize;
        private final Integer fetchSize;
        private final Integer chunkSize;
        private final Boolean previewMaskingEnabled;
        private final Boolean errorLineMaskingEnabled;
        private final Boolean logMaskingEnabled;
        private final Boolean contentEncryptionEnabled;
        private final String encryptionKeyRef;
        private final Boolean downloadRequiresApproval;
        private final String maskingRuleSet;
        private final Boolean enabled;
        private final Integer version;
        private final String description;
    }
}
