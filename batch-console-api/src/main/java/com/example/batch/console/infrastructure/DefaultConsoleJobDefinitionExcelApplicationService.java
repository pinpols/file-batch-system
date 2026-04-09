package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.param.JobDefinitionMaintenanceUpdateParam;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.JobDefinitionExcelImportStore;
import com.example.batch.console.support.JobDefinitionExcelImportStore.JobDefinitionExcelSession;
import com.example.batch.console.support.JobDefinitionExcelImportStore.JobDefinitionRow;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.request.JobDefinitionExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelRowResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelUploadResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
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
 * {@link com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobDefinitionExcelApplicationService implements ConsoleJobDefinitionExcelApplicationService {

    private static final String SHEET = "job_definition";
    private static final List<String> COLUMNS = List.of(
            "tenant_id",
            "job_code",
            "job_name",
            "job_type",
            "queue_code",
            "worker_group",
            "schedule_type",
            "schedule_expr",
            "calendar_code",
            "window_code",
            "retry_policy",
            "retry_max_count",
            "timeout_seconds",
            "shard_strategy",
            "execution_handler",
            "param_schema",
            "default_params",
            "enabled",
            "description"
    );
    private static final Set<String> HEADERS = Set.copyOf(COLUMNS);
    private static final Set<String> RETRY_POLICIES = Set.of("NONE", "FIXED", "EXPONENTIAL");
    private static final Set<String> SHARD_STRATEGIES = Set.of("NONE", "STATIC", "DYNAMIC", "AUTO");
    private static final Set<String> ENABLED_VALUES = Set.of("TRUE", "FALSE");
    private static final Set<String> READ_ONLY_COLUMNS = Set.of(
            "job_type",
            "schedule_type",
            "execution_handler",
            "param_schema",
            "default_params"
    );

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final JobDefinitionMapper jobDefinitionMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final JobDefinitionExcelImportStore importStore;

    @Override
    public ResponseEntity<InputStreamResource> exportJobDefinitions(JobDefinitionQueryRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        List<JobDefinitionEntity> rows = jobDefinitionMapper.selectByQuery(new JobDefinitionQuery(
                tenantId,
                request.getJobCode(),
                request.getJobName(),
                request.getJobType(),
                request.getWorkerGroup(),
                request.getQueueCode(),
                request.getScheduleType(),
                request.getEnabled(),
                null
        ));
        byte[] workbookBytes = writeWorkbook(rows);
        InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
        String fileName = "job-definition-maintenance-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadTemplate() {
        byte[] workbookBytes = writeWorkbook(List.of());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("job-definition-maintenance-template.xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
    }

    @Override
    public ConsoleJobDefinitionExcelUploadResponse upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file is required");
        }
        String tenantId = tenantGuard.resolveTenant(null);
        ParsedWorkbook workbook = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
        String uploadToken = importStore.save(workbook.fileName(), workbook.tenantId(), workbook.rows());
        return new ConsoleJobDefinitionExcelUploadResponse(uploadToken, workbook.fileName(), workbook.rows().size());
    }

    @Override
    public ConsoleJobDefinitionExcelPreviewResponse preview(String uploadToken) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validate(session);
        return new ConsoleJobDefinitionExcelPreviewResponse(
                uploadToken,
                session.fileName(),
                validationResult.totalRows(),
                validationResult.validRows(),
                validationResult.invalidRows(),
                validationResult.rows().stream().map(this::toResponse).toList(),
                validationResult.issues()
        );
    }

    @Override
    @Transactional
    public ConsoleJobDefinitionExcelApplyResponse apply(String uploadToken, JobDefinitionExcelApplyRequest request) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validate(session);
        if (validationResult.invalidRows() > 0) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid job definition rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        String updatedBy = metadata.operatorId();
        int updatedRows = 0;
        for (JobDefinitionRow row : validationResult.rows()) {
            JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(row.tenantId(), row.jobCode());
            if (existing == null) {
                throw new BizException(ResultCode.NOT_FOUND, "job definition not found: " + row.jobCode());
            }
            JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
            param.setTenantId(row.tenantId());
            param.setJobCode(row.jobCode());
            param.setJobName(effective(row.jobName(), existing.getJobName()));
            param.setQueueCode(effective(row.queueCode(), existing.getQueueCode()));
            param.setWorkerGroup(effective(row.workerGroup(), existing.getWorkerGroup()));
            param.setScheduleExpr(effective(row.scheduleExpr(), existing.getScheduleExpr()));
            param.setCalendarCode(effective(row.calendarCode(), existing.getCalendarCode()));
            param.setWindowCode(effective(row.windowCode(), existing.getWindowCode()));
            param.setRetryPolicy(effective(row.retryPolicy(), existing.getRetryPolicy()));
            param.setRetryMaxCount(effective(row.retryMaxCount(), existing.getRetryMaxCount()));
            param.setTimeoutSeconds(effective(row.timeoutSeconds(), existing.getTimeoutSeconds()));
            param.setShardStrategy(effective(row.shardStrategy(), existing.getShardStrategy()));
            param.setEnabled(effective(row.enabled(), existing.getEnabled()));
            param.setDescription(effective(row.description(), existing.getDescription()));
            param.setUpdatedBy(ConsoleTextSanitizer.safeInput(updatedBy, 64));
            jobDefinitionMapper.updateJobDefinitionMaintenance(param);
            logJobChange(row, request.getReason(), updatedBy, metadata.traceId(), existing);
            updatedRows++;
        }
        importStore.remove(uploadToken);
        return new ConsoleJobDefinitionExcelApplyResponse(uploadToken, session.tenantId(), validationResult.totalRows(), updatedRows);
    }

    private ParsedSession loadSession(String uploadToken) {
        JobDefinitionExcelSession session = importStore.get(uploadToken);
        if (session == null) {
            throw new BizException(ResultCode.NOT_FOUND, "excel upload session not found");
        }
        tenantGuard.assertTenantAllowed(session.tenantId());
        return new ParsedSession(session.fileName(), session.tenantId(), session.uploadedAt(), session.rows());
    }

    private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
            }
            List<JobDefinitionRow> rows = parseRows(findSheet(workbook, SHEET), tenantId);
            return new ParsedWorkbook(fileNameOrDefault(originalFileName), tenantId, rows);
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + exception.getMessage());
        }
    }

    private Sheet findSheet(Workbook workbook, String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel sheet missing: " + sheetName);
        }
        return sheet;
    }

    private List<JobDefinitionRow> parseRows(Sheet sheet, String tenantId) {
        List<JobDefinitionRow> rows = new ArrayList<>();
        for (SheetRow rowData : readSheetRows(sheet)) {
            Map<String, String> values = rowData.values();
            rows.add(new JobDefinitionRow(
                    rowData.rowNo(),
                    tenantOrDefault(values.get("tenant_id"), tenantId),
                    normalize(values.get("job_code")),
                    normalize(values.get("job_name")),
                    normalizeEnum(values.get("job_type")),
                    normalize(values.get("queue_code")),
                    normalize(values.get("worker_group")),
                    normalizeEnum(values.get("schedule_type")),
                    normalize(values.get("schedule_expr")),
                    normalize(values.get("calendar_code")),
                    normalize(values.get("window_code")),
                    normalizeEnum(values.get("retry_policy")),
                    parseInteger(values.get("retry_max_count")),
                    parseInteger(values.get("timeout_seconds")),
                    normalizeEnum(values.get("shard_strategy")),
                    normalize(values.get("execution_handler")),
                    normalize(values.get("param_schema")),
                    normalize(values.get("default_params")),
                    parseBoolean(values.get("enabled"), true),
                    normalize(values.get("description"))
            ));
        }
        return rows;
    }

    private List<SheetRow> readSheetRows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel header row is missing for sheet: " + sheet.getSheetName());
        }
        Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
        validateHeaders(sheet.getSheetName(), headerIndex, HEADERS);
        List<SheetRow> rows = new ArrayList<>();
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || rowIsBlank(row, formatter)) {
                continue;
            }
            Map<String, String> rowValues = new LinkedHashMap<>();
            for (String header : COLUMNS) {
                rowValues.put(header, normalize(cellText(row, headerIndex.get(header), formatter)));
            }
            rows.add(new SheetRow(row.getRowNum() + 1, rowValues));
        }
        return rows;
    }

    private ValidationResult validate(ParsedSession session) {
        List<ConsoleJobDefinitionExcelRowIssueResponse> issues = new ArrayList<>();
        List<JobDefinitionRow> validRows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JobDefinitionRow row : session.rows()) {
            List<String> rowIssues = new ArrayList<>();
            String rowKey = row.jobCode();
            if (!hasText(row.tenantId()) || !hasText(row.jobCode())) {
                rowIssues.add("tenant_id and job_code are required");
            }
            if (!seen.add(row.tenantId() + "#" + row.jobCode())) {
                rowIssues.add("duplicate job definition in excel: " + row.tenantId() + "#" + row.jobCode());
            }
            JobDefinitionEntity existing = hasText(row.tenantId()) && hasText(row.jobCode()) ? jobDefinitionMapper.selectByUniqueKey(row.tenantId(), row.jobCode()) : null;
            if (existing == null) {
                rowIssues.add("job definition not found for maintenance: " + row.jobCode());
            } else {
                checkReadOnly(row.jobType(), existing.getJobType(), "job_type", rowIssues);
                checkReadOnly(row.scheduleType(), existing.getScheduleType(), "schedule_type", rowIssues);
                checkReadOnly(row.executionHandler(), existing.getExecutionHandler(), "execution_handler", rowIssues);
                checkReadOnly(row.paramSchema(), existing.getParamSchema(), "param_schema", rowIssues);
                checkReadOnly(row.defaultParams(), existing.getDefaultParams(), "default_params", rowIssues);
            }
            if (hasText(row.retryPolicy()) && !RETRY_POLICIES.contains(row.retryPolicy())) {
                rowIssues.add("retry_policy must be one of " + RETRY_POLICIES);
            }
            if (hasText(row.shardStrategy()) && !SHARD_STRATEGIES.contains(row.shardStrategy())) {
                rowIssues.add("shard_strategy must be one of " + SHARD_STRATEGIES);
            }
            if (hasText(row.enabled() == null ? null : String.valueOf(row.enabled())) && !ENABLED_VALUES.contains(String.valueOf(row.enabled()).toUpperCase(Locale.ROOT))) {
                rowIssues.add("enabled must be TRUE or FALSE");
            }
            if (row.retryMaxCount() != null && row.retryMaxCount() < 0) {
                rowIssues.add("retry_max_count must be >= 0");
            }
            if (row.timeoutSeconds() != null && row.timeoutSeconds() < 0) {
                rowIssues.add("timeout_seconds must be >= 0");
            }
            if (row.paramSchema() != null) {
                try {
                    JsonUtils.fromJson(row.paramSchema(), Object.class);
                } catch (IllegalArgumentException exception) {
                    rowIssues.add("param_schema must be valid JSON");
                }
            }
            if (row.defaultParams() != null) {
                try {
                    JsonUtils.fromJson(row.defaultParams(), Object.class);
                } catch (IllegalArgumentException exception) {
                    rowIssues.add("default_params must be valid JSON");
                }
            }
            if (rowIssues.isEmpty()) {
                validRows.add(row);
            } else {
                issues.add(new ConsoleJobDefinitionExcelRowIssueResponse(SHEET, row.rowNo(), rowKey, row.jobCode(), List.copyOf(rowIssues)));
            }
        }
        return new ValidationResult(session.rows().size(), validRows, issues);
    }

    private void checkReadOnly(String incoming, String current, String field, List<String> rowIssues) {
        if (incoming != null && current != null && !Objects.equals(normalize(incoming), normalize(current))) {
            rowIssues.add(field + " is read-only and must match the existing job definition");
        }
        if (incoming != null && current == null && StringUtils.hasText(incoming)) {
            rowIssues.add(field + " is read-only and must match the existing job definition");
        }
    }

    private byte[] writeWorkbook(List<JobDefinitionEntity> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(50); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet dataSheet = workbook.createSheet(SHEET);
            dataSheet.createFreezePane(0, 1);
            CellStyle headerStyle = createHeaderStyle(workbook);
            writeHeaders(dataSheet, COLUMNS, headerStyle);
            int rowIndex = 1;
            for (JobDefinitionEntity entity : rows) {
                Row row = dataSheet.createRow(rowIndex++);
                writeCell(row, 0, entity.getTenantId());
                writeCell(row, 1, entity.getJobCode());
                writeCell(row, 2, entity.getJobName());
                writeCell(row, 3, entity.getJobType());
                writeCell(row, 4, entity.getQueueCode());
                writeCell(row, 5, entity.getWorkerGroup());
                writeCell(row, 6, entity.getScheduleType());
                writeCell(row, 7, entity.getScheduleExpr());
                writeCell(row, 8, entity.getCalendarCode());
                writeCell(row, 9, entity.getWindowCode());
                writeCell(row, 10, entity.getRetryPolicy());
                writeCell(row, 11, entity.getRetryMaxCount());
                writeCell(row, 12, entity.getTimeoutSeconds());
                writeCell(row, 13, entity.getShardStrategy());
                writeCell(row, 14, entity.getExecutionHandler());
                writeCell(row, 15, entity.getParamSchema());
                writeCell(row, 16, entity.getDefaultParams());
                writeCell(row, 17, entity.getEnabled());
                writeCell(row, 18, entity.getDescription());
            }
            applyValidations(dataSheet);
            setWidths(dataSheet);
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

    private void writeHeaders(Sheet sheet, List<String> columns, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    private void applyValidations(Sheet sheet) {
        addListValidation(sheet, 10, RETRY_POLICIES.toArray(String[]::new));
        addListValidation(sheet, 13, SHARD_STRATEGIES.toArray(String[]::new));
        addListValidation(sheet, 17, ENABLED_VALUES.toArray(String[]::new));
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

    private void setWidths(Sheet sheet) {
        for (int i = 0; i < COLUMNS.size(); i++) {
            sheet.setColumnWidth(i, Math.min(12000, Math.max(18, COLUMNS.get(i).length() + 4) * 256));
        }
    }

    private void createReadmeSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("README");
        sheet.setColumnWidth(0, 16000);
        String[] lines = {
                "job definition safe-field maintenance template",
                "1. Only the editable fields are applied on import.",
                "2. Read-only fields must match the current job definition.",
                "3. Import rows are matched by tenant_id + job_code.",
                "4. Exported workbook can be edited and re-uploaded directly.",
                "5. Import flow is upload -> preview -> apply."
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
                {"retry_policy", "NONE", "no retry"},
                {"retry_policy", "FIXED", "fixed retry"},
                {"retry_policy", "EXPONENTIAL", "exponential retry"},
                {"shard_strategy", "NONE", "no shard"},
                {"shard_strategy", "STATIC", "static shard"},
                {"shard_strategy", "DYNAMIC", "dynamic shard"},
                {"shard_strategy", "AUTO", "auto shard"},
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
        header.createCell(2).setCellValue("row_key");
        header.createCell(3).setCellValue("job_code");
        header.createCell(4).setCellValue("error_reason");
        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 32 * 256);
        sheet.setColumnWidth(3, 24 * 256);
        sheet.setColumnWidth(4, 50 * 256);
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

    private void writeCell(Row row, int columnIndex, Object value) {
        Cell cell = row.createCell(columnIndex);
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private void logJobChange(JobDefinitionRow row, String reason, String updatedBy, String traceId, JobDefinitionEntity existing) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", row.tenantId(),
                "configType", "JOB_DEFINITION",
                "configKey", row.jobCode(),
                "versionNo", 1,
                "changeAction", "BULK_UPDATE",
                "changeResult", "SUCCESS",
                "operatorType", "USER",
                "operatorId", ConsoleTextSanitizer.safeInput(updatedBy, 64),
                "traceId", ConsoleTextSanitizer.safeInput(traceId, 128),
                "changeSummaryJson", JsonUtils.toJson(mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail", mapOf(
                                "jobName", effective(row.jobName(), existing.getJobName()),
                                "queueCode", effective(row.queueCode(), existing.getQueueCode()),
                                "workerGroup", effective(row.workerGroup(), existing.getWorkerGroup()),
                                "scheduleExpr", effective(row.scheduleExpr(), existing.getScheduleExpr()),
                                "retryPolicy", effective(row.retryPolicy(), existing.getRetryPolicy()),
                                "retryMaxCount", effective(row.retryMaxCount(), existing.getRetryMaxCount()),
                                "timeoutSeconds", effective(row.timeoutSeconds(), existing.getTimeoutSeconds()),
                                "shardStrategy", effective(row.shardStrategy(), existing.getShardStrategy()),
                                "enabled", effective(row.enabled(), existing.getEnabled())
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

    private String normalize(String value) {
        return ConsoleTextSanitizer.normalize(value);
    }

    private String normalizeEnum(String value) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private Integer parseInteger(String value) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Boolean parseBoolean(String value, Boolean defaultValue) {
        String normalized = normalize(value);
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
        return defaultValue;
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(normalize(value));
    }

    private String tenantOrDefault(String value, String tenantId) {
        String normalized = normalize(value);
        return StringUtils.hasText(normalized) ? normalized : tenantId;
    }

    private <T> T effective(T incoming, T current) {
        if (incoming == null) {
            return current;
        }
        if (incoming instanceof String str && !StringUtils.hasText(str)) {
            return current;
        }
        return incoming;
    }

    private String fileNameOrDefault(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "job-definition-maintenance.xlsx";
        }
        return fileName;
    }

    private ConsoleJobDefinitionExcelRowResponse toResponse(JobDefinitionRow row) {
        return new ConsoleJobDefinitionExcelRowResponse(
                row.tenantId(),
                row.jobCode(),
                row.jobName(),
                row.jobType(),
                row.queueCode(),
                row.workerGroup(),
                row.scheduleType(),
                row.scheduleExpr(),
                row.calendarCode(),
                row.windowCode(),
                row.retryPolicy(),
                row.retryMaxCount(),
                row.timeoutSeconds(),
                row.shardStrategy(),
                row.executionHandler(),
                row.paramSchema(),
                row.defaultParams(),
                row.enabled(),
                row.description()
        );
    }

    private void validateHeaders(String sheetName, Map<String, Integer> headerIndex, Set<String> requiredHeaders) {
        Set<String> missing = new LinkedHashSet<>(requiredHeaders);
        missing.removeAll(headerIndex.keySet());
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel header missing for sheet " + sheetName + ": " + String.join(", ", missing));
        }
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

    private record ParsedWorkbook(String fileName, String tenantId, List<JobDefinitionRow> rows) {
    }

    private record ParsedSession(String fileName, String tenantId, Instant uploadedAt, List<JobDefinitionRow> rows) {
    }

    private record ValidationResult(int totalRows,
                                    List<JobDefinitionRow> rows,
                                    List<ConsoleJobDefinitionExcelRowIssueResponse> issues) {
        int validRows() {
            return rows.size();
        }

        int invalidRows() {
            return issues.size();
        }
    }

    private record SheetRow(int rowNo, Map<String, String> values) {
    }
}
