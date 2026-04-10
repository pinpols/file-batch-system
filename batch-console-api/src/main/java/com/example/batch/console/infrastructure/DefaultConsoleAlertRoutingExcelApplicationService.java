package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.param.AlertRoutingConfigUpsertParam;
import com.example.batch.console.support.AlertRoutingExcelImportStore;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.AlertRoutingExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleAlertRoutingResponse;
import static com.example.batch.console.support.ConsoleExcelStyles.createHeaderStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;

import com.example.batch.console.support.ConsoleExcelStyles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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
 * {@link ConsoleAlertRoutingExcelApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleAlertRoutingExcelApplicationService implements ConsoleAlertRoutingExcelApplicationService {

    private static final String SHEET_NAME = "alert_routing_config";
    private static final List<String> COLUMNS = List.of(
            "tenant_id",
            "route_code",
            "route_name",
            "team",
            "alert_group",
            "severity",
            "receiver",
            "group_by",
            "group_wait_seconds",
            "group_interval_seconds",
            "repeat_interval_seconds",
            "enabled",
            "description"
    );
    private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR", "CRITICAL");

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final AlertRoutingConfigMapper alertRoutingConfigMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final AlertRoutingExcelImportStore importStore;

    @Override
    public ResponseEntity<InputStreamResource> exportAlertRoutings(AlertRoutingQueryRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        List<Map<String, Object>> rows = alertRoutingConfigMapper.selectByQuery(
                tenantId, request.getRouteCode(), request.getTeam(),
                request.getSeverity(), request.getEnabled(), null);
        byte[] workbookBytes = writeWorkbook(rows);
        String fileName = "alert-routing-config-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(XLSX_MEDIA_TYPE)
                .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadTemplate() {
        byte[] workbookBytes = writeWorkbook(List.of());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("alert-routing-config-template.xlsx").build().toString())
                .contentType(XLSX_MEDIA_TYPE)
                .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
    }

    @Override
    public ConsoleAlertRoutingExcelUploadResponse upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file is required");
        }
        String tenantId = tenantGuard.resolveTenant(null);
        ParsedWorkbook parsed = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
        String uploadToken = importStore.save(parsed.fileName(), parsed.tenantId(), parsed.sheetName(), parsed.rows());
        return new ConsoleAlertRoutingExcelUploadResponse(uploadToken, parsed.fileName(), parsed.sheetName(), parsed.rows().size());
    }

    @Override
    public ConsoleAlertRoutingExcelPreviewResponse preview(String uploadToken) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult result = validateRows(session);
        return new ConsoleAlertRoutingExcelPreviewResponse(
                uploadToken, session.fileName(), session.sheetName(),
                result.totalRows(), result.validRows(), result.invalidRows(),
                result.rows().stream().map(this::toResponse).toList(),
                result.issues());
    }

    @Override
    @Transactional
    public ConsoleAlertRoutingExcelApplyResponse apply(String uploadToken, AlertRoutingExcelApplyRequest request) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult result = validateRows(session);
        if (result.invalidRows() > 0) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid routing rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        String operatorId = metadata.operatorId();
        String traceId = metadata.traceId();
        int inserted = 0;
        int updated = 0;
        for (RoutingRow row : result.rows()) {
            Map<String, Object> existing = alertRoutingConfigMapper.selectByUniqueKey(session.tenantId(), row.routeCode());
            AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
            param.setTenantId(session.tenantId());
            param.setRouteCode(row.routeCode());
            param.setRouteName(row.routeName());
            param.setTeam(row.team());
            param.setAlertGroup(row.alertGroup());
            param.setSeverity(row.severity());
            param.setReceiver(row.receiver());
            param.setGroupBy(row.groupBy());
            param.setGroupWaitSeconds(row.groupWaitSeconds());
            param.setGroupIntervalSeconds(row.groupIntervalSeconds());
            param.setRepeatIntervalSeconds(row.repeatIntervalSeconds());
            param.setEnabled(row.enabled());
            param.setDescription(row.description());
            param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
            param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
            alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
            if (existing == null || existing.isEmpty()) {
                inserted++;
                logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "CREATE");
            } else {
                updated++;
                logChange(session.tenantId(), row, request.getReason(), operatorId, traceId, "PUBLISH");
            }
        }
        importStore.remove(uploadToken);
        return new ConsoleAlertRoutingExcelApplyResponse(uploadToken, session.tenantId(), result.rows().size(), inserted, updated);
    }

    // ── internal ──

    private ParsedSession loadSession(String uploadToken) {
        AlertRoutingExcelImportStore.ExcelImportSession session = importStore.get(uploadToken);
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
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + e.getMessage());
        }
    }

    private ValidationResult validateRows(ParsedSession session) {
        List<RoutingRow> rows = new ArrayList<>();
        List<ConsoleAlertRoutingExcelRowIssueResponse> issues = new ArrayList<>();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> rowValues : session.rows()) {
            List<String> rowIssues = new ArrayList<>();
            RoutingRow row = toRoutingRow(session.tenantId(), rowNo, rowValues, rowIssues);
            String uniqueKey = row.routeCode();
            if (!uniqueKeys.add(uniqueKey)) {
                rowIssues.add("duplicate route code in excel: " + uniqueKey);
            }
            if (rowIssues.isEmpty()) {
                rows.add(row);
            } else {
                issues.add(new ConsoleAlertRoutingExcelRowIssueResponse(rowNo, uniqueKey, row.routeCode(), List.copyOf(rowIssues)));
            }
            rowNo++;
        }
        int totalRows = session.rows().size();
        return new ValidationResult(totalRows, rows.size(), totalRows - rows.size(), rows, issues);
    }

    private RoutingRow toRoutingRow(String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
        String effectiveTenant = normalize(values.get("tenant_id"));
        if (!StringUtils.hasText(effectiveTenant)) {
            effectiveTenant = tenantId;
        } else if (!tenantId.equals(effectiveTenant)) {
            issues.add("tenant_id must match current tenant: " + tenantId);
        }
        return RoutingRow.builder()
                .rowNo(rowNo)
                .tenantId(effectiveTenant)
                .routeCode(requireText(values, "route_code", 128, issues))
                .routeName(requireText(values, "route_name", 256, issues))
                .team(requireText(values, "team", 128, issues))
                .alertGroup(requireText(values, "alert_group", 128, issues))
                .severity(requireEnum(values, "severity", SEVERITIES, 16, issues))
                .receiver(requireText(values, "receiver", 256, issues))
                .groupBy(optionalText(values, "group_by", 512, issues))
                .groupWaitSeconds(optionalInteger(values, "group_wait_seconds", 0, 30, issues))
                .groupIntervalSeconds(optionalInteger(values, "group_interval_seconds", 0, 300, issues))
                .repeatIntervalSeconds(optionalInteger(values, "repeat_interval_seconds", 0, 3600, issues))
                .enabled(optionalBoolean(values, "enabled", true, issues))
                .description(optionalText(values, "description", 1024, issues))
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
        }
        return upper;
    }

    private Integer optionalInteger(Map<String, String> values, String key, int min, int defaultValue, List<String> issues) {
        String normalized = normalize(values.get(key));
        if (!StringUtils.hasText(normalized)) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(normalized);
            if (value < min) {
                issues.add(key + " must be >= " + min);
            }
            return value;
        } catch (NumberFormatException e) {
            issues.add(key + " must be integer");
            return defaultValue;
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

    private String normalize(String value) {
        return ConsoleTextSanitizer.normalize(value);
    }

    private Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> headers = new LinkedHashMap<>();
        for (int i = headerRow.getFirstCellNum(); i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            String header = normalize(formatter.formatCellValue(cell));
            if (StringUtils.hasText(header)) {
                headers.put(header, i);
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
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            String value = normalize(formatter.formatCellValue(row.getCell(i)));
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

    // ── workbook generation ──

    private byte[] writeWorkbook(List<Map<String, Object>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(50); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet dataSheet = workbook.createSheet(SHEET_NAME);
            dataSheet.createFreezePane(0, 1);
            CellStyle headerStyle = createHeaderStyle(workbook);
            Row headerRow = dataSheet.createRow(0);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(COLUMNS.get(i));
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                Row dataRow = dataSheet.createRow(rowIndex++);
                for (int i = 0; i < COLUMNS.size(); i++) {
                    Cell cell = dataRow.createCell(i);
                    Object value = row.get(COLUMNS.get(i));
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
        } catch (IOException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
        }
    }

    private void applyValidations(Sheet sheet) {
        addListValidation(sheet, 5, SEVERITIES.toArray(String[]::new));
        addBooleanValidation(sheet, 11);
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
        for (int col : columns) {
            addListValidation(sheet, col, new String[]{"TRUE", "FALSE"});
        }
    }

    private void createReadmeSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("README");
        sheet.setColumnWidth(0, 16000);
        CellStyle titleStyle = createReadmeTitleStyle(workbook);
        String[] lines = {
                "alert routing config 维护模板",
                "1. 主数据页必须在第一个 sheet。",
                "2. 导出结果可直接修改后再导入。",
                "3. severity 已内置下拉校验（INFO / WARN / ERROR / CRITICAL）。",
                "4. group_by 为逗号分隔的标签列表，可留空。",
                "5. group_wait_seconds / group_interval_seconds / repeat_interval_seconds 对齐 Alertmanager route。",
                "6. 导入流程必须先 upload，再 preview，最后 apply。"
        };
        for (int i = 0; i < lines.length; i++) {
            Row row = sheet.createRow(i);
            row.createCell(0).setCellValue(lines[i]);
            if (i == 0) {
                row.getCell(0).setCellStyle(titleStyle);
            }
        }
    }

    private void createDictSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("DICT");
        sheet.createFreezePane(0, 1);
        CellStyle dictHeaderStyle = createHeaderStyle(workbook);
        writeHeaders(sheet, List.of("field", "value", "description"), dictHeaderStyle);
        String[][] rows = {
                {"severity", "INFO", "informational"},
                {"severity", "WARN", "warning"},
                {"severity", "ERROR", "error"},
                {"severity", "CRITICAL", "critical"},
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
        ConsoleExcelStyles.createValidationSheet(workbook);
    }

    private void logChange(String tenantId, RoutingRow row, String reason, String operatorId, String traceId, String action) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", tenantId,
                "configType", "ALERT_ROUTING",
                "configKey", row.routeCode(),
                "versionNo", 1,
                "changeAction", action,
                "changeResult", "SUCCESS",
                "operatorType", "USER",
                "operatorId", ConsoleTextSanitizer.safeInput(operatorId, 64),
                "traceId", ConsoleTextSanitizer.safeInput(traceId, 128),
                "changeSummaryJson", JsonUtils.toJson(mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail", mapOf(
                                "routeName", row.routeName(),
                                "team", row.team(),
                                "severity", row.severity(),
                                "receiver", row.receiver()
                        )
                ))
        ));
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private String fileNameOrDefault(String fileName) {
        return StringUtils.hasText(fileName) ? fileName : "alert-routing-config.xlsx";
    }

    private ConsoleAlertRoutingResponse toResponse(RoutingRow row) {
        return new ConsoleAlertRoutingResponse(
                null, row.tenantId(), row.routeCode(), row.routeName(),
                row.team(), row.alertGroup(), row.severity(), row.receiver(),
                row.groupBy(), row.groupWaitSeconds(), row.groupIntervalSeconds(),
                row.repeatIntervalSeconds(), row.enabled(), row.description(),
                null, null);
    }

    // ── internal records ──

    private record ParsedWorkbook(String fileName, String tenantId, String sheetName, List<Map<String, String>> rows) {
    }

    private record ParsedSession(String fileName, String tenantId, String sheetName, Instant uploadedAt, List<Map<String, String>> rows) {
    }

    @Builder
    private record RoutingRow(int rowNo, String tenantId, String routeCode, String routeName,
                              String team, String alertGroup, String severity, String receiver,
                              String groupBy, Integer groupWaitSeconds, Integer groupIntervalSeconds,
                              Integer repeatIntervalSeconds, Boolean enabled, String description) {
    }

    private record ValidationResult(int totalRows, int validRows, int invalidRows,
                                    List<RoutingRow> rows,
                                    List<ConsoleAlertRoutingExcelRowIssueResponse> issues) {
    }
}
