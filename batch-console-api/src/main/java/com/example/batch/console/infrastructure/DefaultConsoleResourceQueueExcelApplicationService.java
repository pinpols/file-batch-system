package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleResourceQueueExcelApplicationService;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.mapper.param.ResourceQueueUpsertParam;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleSingleSheetExcelImportSupport;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ResourceQueueExcelImportStore;
import com.example.batch.console.web.request.ResourceQueueExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleResourceQueueExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleResourceQueueExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleResourceQueueExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleResourceQueueExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleResourceQueueResponse;

import lombok.Builder;
import lombok.RequiredArgsConstructor;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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

/** {@link ConsoleResourceQueueExcelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleResourceQueueExcelApplicationService
        implements ConsoleResourceQueueExcelApplicationService {

    private static final String SHEET_NAME = "resource_queue";
    private static final List<String> COLUMNS =
            List.of(
                    "tenant_id",
                    "queue_code",
                    "queue_name",
                    "queue_type",
                    "max_running_jobs",
                    "max_running_partitions",
                    "max_qps",
                    "worker_group",
                    "resource_tag",
                    "priority_policy",
                    "fair_share_weight",
                    "enabled",
                    "description");
    private static final Set<String> REQUIRED_HEADERS = Set.copyOf(COLUMNS);
    private static final Set<String> QUEUE_TYPES = Set.of("IMPORT", "EXPORT", "DISPATCH", "MIXED");
    private static final Set<String> PRIORITY_POLICIES = Set.of("FIFO", "PRIORITY", "FAIR_SHARE");
    private static final Map<String, ConsoleExcelStyles.ColumnGuide> COLUMN_GUIDES =
            Map.ofEntries(
                    Map.entry(
                            "tenant_id",
                            optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", "字符串", "tenant-a")),
                    Map.entry(
                            "queue_code",
                            requiredColumn("队列唯一编码，作为导入匹配键。", "字符串", "QUEUE_IMPORT_01")),
                    Map.entry("queue_name", requiredColumn("控制台展示的队列名称。", "字符串", "导入主队列")),
                    Map.entry(
                            "queue_type",
                            requiredColumn(
                                    "队列类型。",
                                    "枚举",
                                    "IMPORT",
                                    "IMPORT",
                                    "EXPORT",
                                    "DISPATCH",
                                    "MIXED")),
                    Map.entry("max_running_jobs", requiredColumn("最大并行作业数，必须 >= 0。", "整数", "10")),
                    Map.entry(
                            "max_running_partitions",
                            requiredColumn("最大并行分区数，必须 >= 0。", "整数", "20")),
                    Map.entry("max_qps", requiredColumn("最大 QPS 限制，必须 >= 0。", "整数", "100")),
                    Map.entry("worker_group", optionalColumn("指定 Worker 分组。", "字符串", "group-a")),
                    Map.entry(
                            "resource_tag", optionalColumn("资源标签，用于资源隔离。", "字符串", "high-priority")),
                    Map.entry(
                            "priority_policy",
                            requiredColumn(
                                    "优先级策略。", "枚举", "FIFO", "FIFO", "PRIORITY", "FAIR_SHARE")),
                    Map.entry("fair_share_weight", requiredColumn("公平调度权重，必须 >= 1。", "整数", "1")),
                    Map.entry("enabled", optionalColumn("队列是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
                    Map.entry("description", optionalColumn("队列描述信息。", "字符串", "用于导入任务的主队列")));

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final ResourceQueueMapper resourceQueueMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final ResourceQueueExcelImportStore importStore;

    @Override
    public ResponseEntity<InputStreamResource> exportResourceQueues(
            String tenantId, String queueCode, String queueType, Boolean enabled) {
        String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
        List<Map<String, Object>> rows =
                resourceQueueMapper.selectByQuery(
                        resolvedTenantId, queueCode, queueType, enabled, null);
        byte[] workbookBytes = writeWorkbook(rows);
        InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
        String fileName =
                "resource-queue-" + resolvedTenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadTemplate() {
        byte[] workbookBytes = writeWorkbook(List.of());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("resource-queue-template.xlsx")
                                .build()
                                .toString())
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
    }

    @Override
    public ConsoleResourceQueueExcelUploadResponse upload(MultipartFile file) throws IOException {
        Guard.require(file != null && !file.isEmpty(), "file is required");
        String tenantId = tenantGuard.resolveTenant(null);
        ConsoleSingleSheetExcelImportSupport.ParsedWorkbook parsedWorkbook =
                ConsoleSingleSheetExcelImportSupport.parseWorkbook(
                        file.getBytes(),
                        tenantId,
                        file.getOriginalFilename(),
                        "resource-queue.xlsx",
                        COLUMNS,
                        REQUIRED_HEADERS);
        String uploadToken =
                importStore.save(
                        parsedWorkbook.fileName(),
                        parsedWorkbook.tenantId(),
                        parsedWorkbook.sheetName(),
                        parsedWorkbook.rows());
        return new ConsoleResourceQueueExcelUploadResponse(
                uploadToken,
                parsedWorkbook.fileName(),
                parsedWorkbook.sheetName(),
                parsedWorkbook.rows().size());
    }

    @Override
    public ConsoleResourceQueueExcelPreviewResponse preview(String uploadToken) {
        ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        return new ConsoleResourceQueueExcelPreviewResponse(
                uploadToken,
                session.fileName(),
                session.sheetName(),
                validationResult.totalRows(),
                validationResult.validRows(),
                validationResult.invalidRows(),
                validationResult.rows().stream().map(this::toResponse).toList(),
                validationResult.issues());
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
        ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        byte[] workbookBytes = writePreviewWorkbook(session, validationResult);
        return ConsoleSingleSheetExcelImportSupport.excelResponse(
                ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()),
                workbookBytes);
    }

    @Override
    @Transactional
    public ConsoleResourceQueueExcelApplyResponse apply(
            String uploadToken, ResourceQueueExcelApplyRequest request) {
        ConsoleSingleSheetExcelImportSupport.ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validateRows(session);
        if (validationResult.invalidRows() > 0) {
            throw new BizException(
                    ResultCode.INVALID_ARGUMENT, "excel contains invalid resource queue rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        String operatorId = metadata.operatorId();
        String traceId = metadata.traceId();
        int inserted = 0;
        int updated = 0;
        for (QueueRow row : validationResult.rows()) {
            Map<String, Object> existing =
                    resourceQueueMapper.selectByUniqueKey(session.tenantId(), row.queueCode());
            ResourceQueueUpsertParam param = new ResourceQueueUpsertParam();
            param.setTenantId(session.tenantId());
            param.setQueueCode(row.queueCode());
            param.setQueueName(row.queueName());
            param.setQueueType(row.queueType());
            param.setMaxRunningJobs(row.maxRunningJobs());
            param.setMaxRunningPartitions(row.maxRunningPartitions());
            param.setMaxQps(row.maxQps());
            param.setWorkerGroup(row.workerGroup());
            param.setResourceTag(row.resourceTag());
            param.setPriorityPolicy(row.priorityPolicy());
            param.setFairShareWeight(row.fairShareWeight());
            param.setEnabled(row.enabled());
            param.setDescription(row.description());
            param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
            param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
            resourceQueueMapper.upsertResourceQueue(param);
            if (existing == null || existing.isEmpty()) {
                inserted++;
                logChange(
                        session.tenantId(),
                        row,
                        request.getReason(),
                        operatorId,
                        traceId,
                        "CREATE");
            } else {
                updated++;
                logChange(
                        session.tenantId(),
                        row,
                        request.getReason(),
                        operatorId,
                        traceId,
                        "PUBLISH");
            }
        }
        importStore.remove(uploadToken);
        return new ConsoleResourceQueueExcelApplyResponse(
                uploadToken, session.tenantId(), validationResult.rows().size(), inserted, updated);
    }

    private ConsoleSingleSheetExcelImportSupport.ParsedSession loadSession(String uploadToken) {
        return ConsoleSingleSheetExcelImportSupport.loadSession(
                uploadToken, importStore.get(uploadToken), tenantGuard);
    }

    private ValidationResult validateRows(
            ConsoleSingleSheetExcelImportSupport.ParsedSession session) {
        List<QueueRow> rows = new ArrayList<>();
        List<ConsoleResourceQueueExcelRowIssueResponse> issues = new ArrayList<>();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> rowValues : session.rows()) {
            List<String> rowIssues = new ArrayList<>();
            QueueRow row = toQueueRow(session.tenantId(), rowNo, rowValues, rowIssues);
            String uniqueKey = row.queueCode();
            if (!uniqueKeys.add(uniqueKey)) {
                rowIssues.add("duplicate queue code in excel: " + uniqueKey);
            }
            if (rowIssues.isEmpty()) {
                rows.add(row);
            } else {
                issues.add(
                        new ConsoleResourceQueueExcelRowIssueResponse(
                                rowNo, uniqueKey, row.queueCode(), List.copyOf(rowIssues)));
            }
            rowNo++;
        }
        int totalRows = session.rows().size();
        return ValidationResult.builder()
                .counts(
                        ValidationCounts.builder()
                                .totalRows(totalRows)
                                .validRows(rows.size())
                                .invalidRows(totalRows - rows.size())
                                .build())
                .data(ValidationData.builder().rows(rows).issues(issues).build())
                .build();
    }

    private QueueRow toQueueRow(
            String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
        String effectiveTenant = normalize(values.get("tenant_id"));
        if (!StringUtils.hasText(effectiveTenant)) {
            effectiveTenant = tenantId;
        } else if (!tenantId.equals(effectiveTenant)) {
            issues.add("tenant_id must match current tenant: " + tenantId);
        }
        return QueueRow.builder()
                .identity(
                        QueueIdentity.builder()
                                .rowNo(rowNo)
                                .tenantId(effectiveTenant)
                                .queueCode(requireText(values, "queue_code", 128, issues))
                                .build())
                .definition(
                        QueueDefinition.builder()
                                .queueName(requireText(values, "queue_name", 256, issues))
                                .queueType(
                                        requireEnum(values, "queue_type", QUEUE_TYPES, 32, issues))
                                .maxRunningJobs(
                                        requireInteger(values, "max_running_jobs", 0, issues))
                                .maxRunningPartitions(
                                        requireInteger(values, "max_running_partitions", 0, issues))
                                .maxQps(requireInteger(values, "max_qps", 0, issues))
                                .build())
                .scheduling(
                        QueueScheduling.builder()
                                .workerGroup(optionalText(values, "worker_group", 128, issues))
                                .resourceTag(optionalText(values, "resource_tag", 64, issues))
                                .priorityPolicy(
                                        requireEnum(
                                                values,
                                                "priority_policy",
                                                PRIORITY_POLICIES,
                                                32,
                                                issues))
                                .fairShareWeight(
                                        requireInteger(values, "fair_share_weight", 1, issues))
                                .enabled(optionalBoolean(values, "enabled", true, issues))
                                .description(optionalText(values, "description", 512, issues))
                                .build())
                .build();
    }

    private String requireText(
            Map<String, String> values, String key, int maxLength, List<String> issues) {
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

    private String optionalText(
            Map<String, String> values, String key, int maxLength, List<String> issues) {
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

    private String requireEnum(
            Map<String, String> values,
            String key,
            Set<String> allowed,
            int maxLength,
            List<String> issues) {
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

    private Integer requireInteger(
            Map<String, String> values, String key, int min, List<String> issues) {
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

    private Boolean optionalBoolean(
            Map<String, String> values, String key, Boolean defaultValue, List<String> issues) {
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

    private byte[] writeWorkbook(List<Map<String, Object>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet dataSheet = workbook.createSheet(SHEET_NAME);
            dataSheet.createFreezePane(0, 1);
            writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook);
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
            setWidths(dataSheet, COLUMNS);
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

    private byte[] writePreviewWorkbook(
            ConsoleSingleSheetExcelImportSupport.ParsedSession session,
            ValidationResult validationResult) {
        List<WorkbookIssue> workbookIssues =
                validationResult.issues().stream()
                        .flatMap(
                                issue ->
                                        ConsoleExcelPreviewWorkbookSupport.expandIssues(
                                                SHEET_NAME,
                                                issue.rowNo(),
                                                issue.messages(),
                                                COLUMNS)
                                                .stream())
                        .toList();
        return ConsoleSingleSheetExcelImportSupport.writePreviewWorkbook(
                session,
                COLUMNS,
                COLUMN_GUIDES,
                this::applyValidations,
                workbook -> {
                    createReadmeSheet(workbook);
                    createDictSheet(workbook);
                    createValidationSheet(workbook);
                },
                workbookIssues,
                1,
                "failed to generate preview excel workbook");
    }

    private void applyValidations(Sheet sheet) {
        addDropdownValidation(
                sheet, 3, QUEUE_TYPES.toArray(String[]::new), "queue_type 填写提示", "请从下拉列表中选择队列类型。");
        addDropdownValidation(
                sheet,
                9,
                PRIORITY_POLICIES.toArray(String[]::new),
                "priority_policy 填写提示",
                "请从下拉列表中选择优先级策略。");
        addBooleanValidation(sheet, new int[] {11}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
    }

    private void createReadmeSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("README");
        sheet.setColumnWidth(0, 16000);
        CellStyle titleStyle = createReadmeTitleStyle(workbook);
        String[] lines = {
            "resource queue config maintenance template",
            "1. Orange headers mark required fields. Hover the header to see field rules and"
                + " examples.",
            "2. queue_code is the unique key used during preview and apply.",
            "3. queue_type, priority_policy, and enabled have built-in dropdown validation.",
            "4. max_running_jobs, max_running_partitions, max_qps must be >= 0; fair_share_weight"
                + " must be >= 1.",
            "5. Import flow is upload -> preview -> apply."
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
        CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
        writeHeaders(sheet, List.of("field", "value", "description"), dictHeaderStyle);
        String[][] rows = {
            {"queue_type", "IMPORT", "import queue"},
            {"queue_type", "EXPORT", "export queue"},
            {"queue_type", "DISPATCH", "dispatch queue"},
            {"queue_type", "MIXED", "mixed queue"},
            {"priority_policy", "FIFO", "first in first out"},
            {"priority_policy", "PRIORITY", "priority based"},
            {"priority_policy", "FAIR_SHARE", "fair share scheduling"},
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

    private void logChange(
            String tenantId,
            QueueRow row,
            String reason,
            String operatorId,
            String traceId,
            String action) {
        configChangeLogMapper.insertConfigChangeLog(
                mapOf(
                        "tenantId",
                        tenantId,
                        "configType",
                        "RESOURCE_QUEUE",
                        "configKey",
                        row.queueCode(),
                        "versionNo",
                        1,
                        "changeAction",
                        action,
                        "changeResult",
                        "SUCCESS",
                        "operatorType",
                        "USER",
                        "operatorId",
                        ConsoleTextSanitizer.safeInput(operatorId, 64),
                        "traceId",
                        ConsoleTextSanitizer.safeInput(traceId, 128),
                        "changeSummaryJson",
                        JsonUtils.toJson(
                                mapOf(
                                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                                        "detail",
                                                mapOf(
                                                        "queueName", row.queueName(),
                                                        "queueType", row.queueType(),
                                                        "maxRunningJobs", row.maxRunningJobs(),
                                                        "priorityPolicy", row.priorityPolicy(),
                                                        "fairShareWeight",
                                                                row.fairShareWeight())))));
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private ConsoleResourceQueueResponse toResponse(QueueRow row) {
        return new ConsoleResourceQueueResponse(
                null,
                row.tenantId(),
                row.queueCode(),
                row.queueName(),
                row.queueType(),
                row.maxRunningJobs(),
                row.maxRunningPartitions(),
                row.maxQps(),
                row.workerGroup(),
                row.resourceTag(),
                row.priorityPolicy(),
                row.fairShareWeight(),
                row.enabled(),
                row.description(),
                null,
                null);
    }

    @Builder
    private record ValidationResult(ValidationCounts counts, ValidationData data) {
        int totalRows() {
            return counts.totalRows();
        }

        int validRows() {
            return counts.validRows();
        }

        int invalidRows() {
            return counts.invalidRows();
        }

        List<QueueRow> rows() {
            return data.rows();
        }

        List<ConsoleResourceQueueExcelRowIssueResponse> issues() {
            return data.issues();
        }
    }

    @Builder
    private record ValidationCounts(int totalRows, int validRows, int invalidRows) {}

    @Builder
    private record ValidationData(
            List<QueueRow> rows, List<ConsoleResourceQueueExcelRowIssueResponse> issues) {}

    @Builder
    private record QueueRow(
            QueueIdentity identity, QueueDefinition definition, QueueScheduling scheduling) {
        int rowNo() {
            return identity.rowNo();
        }

        String tenantId() {
            return identity.tenantId();
        }

        String queueCode() {
            return identity.queueCode();
        }

        String queueName() {
            return definition.queueName();
        }

        String queueType() {
            return definition.queueType();
        }

        Integer maxRunningJobs() {
            return definition.maxRunningJobs();
        }

        Integer maxRunningPartitions() {
            return definition.maxRunningPartitions();
        }

        Integer maxQps() {
            return definition.maxQps();
        }

        String workerGroup() {
            return scheduling.workerGroup();
        }

        String resourceTag() {
            return scheduling.resourceTag();
        }

        String priorityPolicy() {
            return scheduling.priorityPolicy();
        }

        Integer fairShareWeight() {
            return scheduling.fairShareWeight();
        }

        Boolean enabled() {
            return scheduling.enabled();
        }

        String description() {
            return scheduling.description();
        }
    }

    @Builder
    private record QueueIdentity(int rowNo, String tenantId, String queueCode) {}

    @Builder
    private record QueueDefinition(
            String queueName,
            String queueType,
            Integer maxRunningJobs,
            Integer maxRunningPartitions,
            Integer maxQps) {}

    @Builder
    private record QueueScheduling(
            String workerGroup,
            String resourceTag,
            String priorityPolicy,
            Integer fairShareWeight,
            Boolean enabled,
            String description) {}
}
