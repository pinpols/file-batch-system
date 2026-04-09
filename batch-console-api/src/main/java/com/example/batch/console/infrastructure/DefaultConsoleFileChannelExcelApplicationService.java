package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleFileChannelExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.param.FileChannelConfigUpsertParam;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.FileChannelExcelImportStore;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.FileChannelExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleFileChannelExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleFileChannelExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleFileChannelExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleFileChannelExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleFileChannelResponse;
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
import lombok.RequiredArgsConstructor;
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
 * {@link com.example.batch.console.application.ConsoleFileChannelExcelApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleFileChannelExcelApplicationService implements ConsoleFileChannelExcelApplicationService {

    private static final String SHEET_NAME = "file_channel_config";
    private static final List<String> COLUMNS = List.of(
            "tenant_id",
            "channel_code",
            "channel_name",
            "channel_type",
            "target_endpoint",
            "auth_type",
            "config_json",
            "receipt_policy",
            "timeout_seconds",
            "enabled"
    );
    private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
    private static final Set<String> CHANNEL_TYPES = Set.of("SFTP", "API", "EMAIL", "NAS", "OSS", "LOCAL");
    private static final Set<String> AUTH_TYPES = Set.of("NONE", "PASSWORD", "KEY_PAIR", "TOKEN", "OAUTH2", "CUSTOM");
    private static final Set<String> RECEIPT_POLICIES = Set.of("NONE", "SYNC", "ASYNC", "POLLING");

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final FileChannelConfigMapper fileChannelConfigMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final FileChannelExcelImportStore importStore;

    @Override
    public ResponseEntity<InputStreamResource> exportFileChannels(FileChannelQueryRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        List<Map<String, Object>> rows = fileChannelConfigMapper.selectByQuery(
                tenantId,
                request.getChannelCode(),
                request.getChannelType(),
                request.getEnabled(),
                null
        );
        byte[] workbookBytes = writeWorkbook(rows);
        InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
        String fileName = "file-channel-config-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadTemplate() {
        byte[] workbookBytes = writeWorkbook(List.of());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("file-channel-config-template.xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
    }

    @Override
    public ConsoleFileChannelExcelUploadResponse upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file is required");
        }
        String tenantId = tenantGuard.resolveTenant(null);
        ParsedWorkbook parsedWorkbook = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
        String uploadToken = importStore.save(parsedWorkbook.fileName(), parsedWorkbook.tenantId(), parsedWorkbook.sheetName(), parsedWorkbook.rows());
        return new ConsoleFileChannelExcelUploadResponse(uploadToken, parsedWorkbook.fileName(), parsedWorkbook.sheetName(), parsedWorkbook.rows().size());
    }

    @Override
    public ConsoleFileChannelExcelPreviewResponse preview(String uploadToken) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        return new ConsoleFileChannelExcelPreviewResponse(
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
    public ConsoleFileChannelExcelApplyResponse apply(String uploadToken, FileChannelExcelApplyRequest request) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        if (validationResult.invalidRows() > 0) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid channel rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        String operatorId = metadata.operatorId();
        String traceId = metadata.traceId();
        int inserted = 0;
        int updated = 0;
        for (ChannelRow row : validationResult.rows()) {
            Map<String, Object> existing = fileChannelConfigMapper.selectByUniqueKey(session.tenantId(), row.channelCode());
            FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
            param.setTenantId(session.tenantId());
            param.setChannelCode(row.channelCode());
            param.setChannelName(row.channelName());
            param.setChannelType(row.channelType());
            param.setTargetEndpoint(row.targetEndpoint());
            param.setAuthType(row.authType());
            param.setConfigJson(row.configJson());
            param.setReceiptPolicy(row.receiptPolicy());
            param.setTimeoutSeconds(row.timeoutSeconds());
            param.setEnabled(row.enabled());
            param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
            param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
            fileChannelConfigMapper.upsertFileChannelConfig(param);
            if (existing == null || existing.isEmpty()) {
                inserted++;
                logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "CREATE");
            } else {
                updated++;
                logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "PUBLISH");
            }
        }
        importStore.remove(uploadToken);
        return new ConsoleFileChannelExcelApplyResponse(uploadToken, session.tenantId(), validationResult.rows().size(), inserted, updated);
    }

    private ParsedSession loadSession(String uploadToken) {
        FileChannelExcelImportStore.ExcelImportSession session = importStore.get(uploadToken);
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
        List<ChannelRow> rows = new ArrayList<>();
        List<ConsoleFileChannelExcelRowIssueResponse> issues = new ArrayList<>();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> rowValues : session.rows()) {
            List<String> rowIssues = new ArrayList<>();
            ChannelRow row = toChannelRow(session.tenantId(), rowNo, rowValues, rowIssues);
            String uniqueKey = row.channelCode();
            if (!uniqueKeys.add(uniqueKey)) {
                rowIssues.add("duplicate channel code in excel: " + uniqueKey);
            }
            if (rowIssues.isEmpty()) {
                rows.add(row);
            } else {
                issues.add(new ConsoleFileChannelExcelRowIssueResponse(rowNo, uniqueKey, row.channelCode(), List.copyOf(rowIssues)));
            }
            rowNo++;
        }
        int totalRows = session.rows().size();
        return ValidationResult.builder()
                .counts(ValidationCounts.builder()
                        .totalRows(totalRows)
                        .validRows(rows.size())
                        .invalidRows(totalRows - rows.size())
                        .build())
                .data(ValidationData.builder()
                        .rows(rows)
                        .issues(issues)
                        .build())
                .build();
    }

    private ChannelRow toChannelRow(String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
        String effectiveTenant = normalize(values.get("tenant_id"));
        if (!StringUtils.hasText(effectiveTenant)) {
            effectiveTenant = tenantId;
        } else if (!tenantId.equals(effectiveTenant)) {
            issues.add("tenant_id must match current tenant: " + tenantId);
        }
        return ChannelRow.builder()
                .identity(ChannelIdentity.builder()
                        .rowNo(rowNo)
                        .tenantId(effectiveTenant)
                        .channelCode(requireText(values, "channel_code", 128, issues))
                        .build())
                .definition(ChannelDefinition.builder()
                        .channelName(requireText(values, "channel_name", 256, issues))
                        .channelType(requireEnum(values, "channel_type", CHANNEL_TYPES, 32, issues))
                        .targetEndpoint(optionalText(values, "target_endpoint", 1024, issues))
                        .authType(requireEnum(values, "auth_type", AUTH_TYPES, 32, issues))
                        .build())
                .delivery(ChannelDelivery.builder()
                        .configJson(requireJson(values, "config_json", issues))
                        .receiptPolicy(requireEnum(values, "receipt_policy", RECEIPT_POLICIES, 32, issues))
                        .timeoutSeconds(requireInteger(values, "timeout_seconds", 0, issues))
                        .enabled(optionalBoolean(values, "enabled", true, issues))
                        .build())
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
        String normalizedUpper = normalized.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalizedUpper)) {
            issues.add(key + " must be one of " + allowed);
        }
        return normalizedUpper;
    }

    private Integer requireInteger(Map<String, String> values, String key, int min, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            issues.add(key + " is required");
            return min;
        }
        try {
            int value = Integer.parseInt(normalized);
            if (value < min) {
                issues.add(key + " must be >= " + min);
            }
            return value;
        } catch (NumberFormatException exception) {
            issues.add(key + " must be integer");
            return min;
        }
    }

    private Boolean optionalBoolean(Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            return defaultValue;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (List.of("TRUE", "Y", "1", "YES").contains(upper)) {
            return true;
        }
        if (List.of("FALSE", "N", "0", "NO").contains(upper)) {
            return false;
        }
        issues.add(key + " must be boolean");
        return defaultValue;
    }

    private String requireJson(Map<String, String> values, String key, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            issues.add(key + " is required");
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
                    cell.setCellValue(value == null ? "" : String.valueOf(value));
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
        addListValidation(sheet, 3, CHANNEL_TYPES.toArray(String[]::new));
        addListValidation(sheet, 5, AUTH_TYPES.toArray(String[]::new));
        addListValidation(sheet, 7, RECEIPT_POLICIES.toArray(String[]::new));
        addBooleanValidation(sheet, 9);
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
                "file channel config 维护模板",
                "1. 主数据页必须在第一个 sheet。",
                "2. 导出结果可直接修改后再导入。",
                "3. 通道类型、认证方式、回执策略、启用状态已内置下拉校验。",
                "4. config_json 请保持合法 JSON。",
                "5. 导入流程必须先 upload，再 preview，最后 apply。"
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
                {"channel_type", "SFTP", "sftp channel"},
                {"channel_type", "API", "api channel"},
                {"channel_type", "EMAIL", "email channel"},
                {"channel_type", "NAS", "nas channel"},
                {"channel_type", "OSS", "object storage"},
                {"channel_type", "LOCAL", "local filesystem"},
                {"auth_type", "NONE", "no auth"},
                {"auth_type", "PASSWORD", "password auth"},
                {"auth_type", "KEY_PAIR", "key pair auth"},
                {"auth_type", "TOKEN", "token auth"},
                {"auth_type", "OAUTH2", "oauth2 auth"},
                {"auth_type", "CUSTOM", "custom auth"},
                {"receipt_policy", "NONE", "no receipt"},
                {"receipt_policy", "SYNC", "synchronous receipt"},
                {"receipt_policy", "ASYNC", "asynchronous receipt"},
                {"receipt_policy", "POLLING", "polling receipt"},
                {"enabled", "TRUE", "enabled"},
                {"enabled", "FALSE", "disabled"}
        };
        for (int i = 0; i < rows.length; i++) {
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(rows[i][0]);
            row.createCell(1).setCellValue(rows[i][1]);
            row.createCell(2).setCellValue(rows[i][2]);
        }
        sheet.setColumnWidth(0, 24 * 256);
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

    private void logChange(String tenantId, ChannelRow row, String reason, String operatorId, String traceId, String action) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", tenantId,
                "configType", "FILE_CHANNEL",
                "configKey", row.channelCode(),
                "versionNo", 1,
                "changeAction", action,
                "changeResult", "SUCCESS",
                "operatorType", "USER",
                "operatorId", ConsoleTextSanitizer.safeInput(operatorId, 64),
                "traceId", ConsoleTextSanitizer.safeInput(traceId, 128),
                "changeSummaryJson", JsonUtils.toJson(mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail", mapOf(
                                "channelName", row.channelName(),
                                "channelType", row.channelType(),
                                "authType", row.authType(),
                                "receiptPolicy", row.receiptPolicy(),
                                "timeoutSeconds", row.timeoutSeconds()
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
            return "file-channel-config.xlsx";
        }
        return fileName;
    }

    private ConsoleFileChannelResponse toResponse(ChannelRow row) {
        return new ConsoleFileChannelResponse(
                null,
                row.tenantId(),
                row.channelCode(),
                row.channelName(),
                row.channelType(),
                row.targetEndpoint(),
                row.authType(),
                row.configJson(),
                row.receiptPolicy(),
                row.timeoutSeconds(),
                row.enabled(),
                null,
                null
        );
    }

    private record ParsedWorkbook(String fileName, String tenantId, String sheetName, List<Map<String, String>> rows) {
    }

    private record ParsedSession(String fileName, String tenantId, String sheetName, Instant uploadedAt, List<Map<String, String>> rows) {
    }

    @Builder
    private record ValidationResult(ValidationCounts counts,
                                    ValidationData data) {
        int totalRows() {
            return counts.totalRows();
        }

        int validRows() {
            return counts.validRows();
        }

        int invalidRows() {
            return counts.invalidRows();
        }

        List<ChannelRow> rows() {
            return data.rows();
        }

        List<ConsoleFileChannelExcelRowIssueResponse> issues() {
            return data.issues();
        }
    }

    @Builder
    private record ValidationCounts(int totalRows,
                                    int validRows,
                                    int invalidRows) {
    }

    @Builder
    private record ValidationData(List<ChannelRow> rows,
                                  List<ConsoleFileChannelExcelRowIssueResponse> issues) {
    }

    @Builder
    private record ChannelRow(ChannelIdentity identity,
                              ChannelDefinition definition,
                              ChannelDelivery delivery) {
        int rowNo() {
            return identity.rowNo();
        }

        String tenantId() {
            return identity.tenantId();
        }

        String channelCode() {
            return identity.channelCode();
        }

        String channelName() {
            return definition.channelName();
        }

        String channelType() {
            return definition.channelType();
        }

        String targetEndpoint() {
            return definition.targetEndpoint();
        }

        String authType() {
            return definition.authType();
        }

        String configJson() {
            return delivery.configJson();
        }

        String receiptPolicy() {
            return delivery.receiptPolicy();
        }

        Integer timeoutSeconds() {
            return delivery.timeoutSeconds();
        }

        Boolean enabled() {
            return delivery.enabled();
        }
    }

    @Builder
    private record ChannelIdentity(int rowNo,
                                   String tenantId,
                                   String channelCode) {
    }

    @Builder
    private record ChannelDefinition(String channelName,
                                     String channelType,
                                     String targetEndpoint,
                                     String authType) {
    }

    @Builder
    private record ChannelDelivery(String configJson,
                                   String receiptPolicy,
                                   Integer timeoutSeconds,
                                   Boolean enabled) {
    }
}
