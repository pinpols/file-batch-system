package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createOptionalMarkStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.createRequiredMarkStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleTenantConfigPackageExcelApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.param.AlertRoutingConfigUpsertParam;
import com.example.batch.console.mapper.param.FileChannelConfigUpsertParam;
import com.example.batch.console.mapper.param.JobDefinitionMaintenanceUpdateParam;
import com.example.batch.console.mapper.param.WorkflowDefinitionUpsertParam;
import com.example.batch.console.mapper.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.mapper.param.WorkflowNodeUpsertParam;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.TenantConfigPackageExcelImportStore;
import com.example.batch.console.support.TenantConfigPackageExcelImportStore.PackageExcelSession;
import com.example.batch.console.web.request.TenantConfigPackageExcelApplyRequest;
import com.example.batch.console.web.response.TenantConfigPackageExcelApplyResponse;
import com.example.batch.console.web.response.TenantConfigPackageExcelPreviewResponse;
import com.example.batch.console.web.response.TenantConfigPackageExcelPreviewResponse.IssueDto;
import com.example.batch.console.web.response.TenantConfigPackageExcelPreviewResponse.SheetStats;
import com.example.batch.console.web.response.TenantConfigPackageExcelUploadResponse;

import lombok.RequiredArgsConstructor;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.util.stream.Collectors;

/** 租户配置包 Excel 导入服务：8 sheet 单事务写库，含跨 sheet 依赖校验。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DefaultConsoleTenantConfigPackageExcelApplicationService
        implements ConsoleTenantConfigPackageExcelApplicationService {

    // ── sheet names ───────────────────────────────────────────────────────────
    private static final String JOB_SHEET = "job_definition";
    private static final String CHANNEL_SHEET = "file_channel_config";
    private static final String ROUTING_SHEET = "alert_routing_config";
    private static final String PIPELINE_SHEET = "pipeline_definition";
    private static final String STEP_SHEET = "pipeline_step_definition";
    private static final String WF_DEF_SHEET = "workflow_definition";
    private static final String WF_NODE_SHEET = "workflow_node";
    private static final String WF_EDGE_SHEET = "workflow_edge";

    // ── columns ───────────────────────────────────────────────────────────────
    private static final List<String> JOB_COLUMNS = List.of(
            "tenant_id", "job_code", "job_name", "job_type", "biz_type",
            "queue_code", "worker_group", "schedule_type", "schedule_expr",
            "calendar_code", "window_code", "retry_policy", "retry_max_count",
            "timeout_seconds", "shard_strategy", "execution_handler",
            "param_schema", "default_params", "enabled", "description");

    private static final List<String> CHANNEL_COLUMNS = List.of(
            "tenant_id", "channel_code", "channel_name", "channel_type",
            "target_endpoint", "auth_type", "config_json", "receipt_policy",
            "timeout_seconds", "enabled");

    private static final List<String> ROUTING_COLUMNS = List.of(
            "tenant_id", "route_code", "route_name", "team", "alert_group",
            "severity", "receiver", "group_by", "group_wait_seconds",
            "group_interval_seconds", "repeat_interval_seconds", "enabled", "description");

    private static final List<String> PIPELINE_COLUMNS = List.of(
            "tenant_id", "job_code", "pipeline_name", "pipeline_type",
            "biz_type", "worker_group", "version", "enabled", "description");

    private static final List<String> STEP_COLUMNS = List.of(
            "job_code", "version", "step_code", "step_name", "stage_code",
            "step_order", "impl_code", "step_params", "timeout_seconds",
            "retry_policy", "retry_max_count", "enabled");

    private static final List<String> WF_DEF_COLUMNS = List.of(
            "tenant_id", "workflow_code", "workflow_name", "workflow_type",
            "version", "enabled", "description");

    private static final List<String> WF_NODE_COLUMNS = List.of(
            "tenant_id", "workflow_code", "workflow_version", "node_code",
            "node_name", "node_type", "related_job_code", "related_pipeline_code",
            "worker_group", "window_code", "node_order", "retry_policy",
            "retry_max_count", "timeout_seconds", "node_params", "enabled");

    private static final List<String> WF_EDGE_COLUMNS = List.of(
            "tenant_id", "workflow_code", "workflow_version",
            "from_node_code", "to_node_code", "edge_type", "condition_expr", "enabled");

    // ── enum sets ─────────────────────────────────────────────────────────────
    private static final Set<String> JOB_TYPES =
            Set.of("GENERAL", "IMPORT", "EXPORT", "DISPATCH", "WORKFLOW");
    private static final Set<String> SCHEDULE_TYPES =
            Set.of("CRON", "FIXED_RATE", "MANUAL", "EVENT", "ONE_TIME");
    private static final Set<String> RETRY_POLICIES = Set.of("NONE", "FIXED", "EXPONENTIAL");
    private static final Set<String> SHARD_STRATEGIES = Set.of("NONE", "STATIC", "DYNAMIC", "AUTO");
    private static final Set<String> CHANNEL_TYPES =
            Set.of("SFTP", "API", "EMAIL", "NAS", "OSS", "LOCAL", "API_PUSH");
    private static final Set<String> AUTH_TYPES =
            Set.of("NONE", "PASSWORD", "KEY_PAIR", "TOKEN", "OAUTH2", "CUSTOM");
    private static final Set<String> RECEIPT_POLICIES = Set.of("NONE", "SYNC", "ASYNC", "POLLING");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR", "CRITICAL");
    private static final Set<String> PIPELINE_TYPES = Set.of("IMPORT", "EXPORT", "DISPATCH");
    private static final Set<String> STAGE_CODES = Set.of(
            "RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD",
            "GENERATE", "TRANSFER", "DISPATCH", "ACK");
    private static final Set<String> WORKFLOW_TYPES = Set.of("DAG", "PIPELINE", "MIXED");
    private static final Set<String> NODE_TYPES =
            Set.of("TASK", "GATEWAY", "FILE_STEP", "START", "END", "JOB");
    private static final Set<String> EDGE_TYPES =
            Set.of("SUCCESS", "FAILURE", "CONDITION", "ALWAYS");

    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    // ── dependencies ──────────────────────────────────────────────────────────
    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final TenantConfigPackageExcelImportStore importStore;
    private final JobDefinitionMapper jobDefinitionMapper;
    private final FileChannelConfigMapper fileChannelConfigMapper;
    private final AlertRoutingConfigMapper alertRoutingConfigMapper;
    private final PipelineDefinitionMapper pipelineDefinitionMapper;
    private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;

    // ── public API ────────────────────────────────────────────────────────────

    @Override
    public ResponseEntity<InputStreamResource> exportPackage(String tenantId) {
        String tid = tenantGuard.resolveTenant(tenantId);
        List<Map<String, Object>> jobs = toJobRows(
                jobDefinitionMapper.selectByQuery(
                        new JobDefinitionQuery(tid, null, null, null, null, null, null, null, null)));
        List<Map<String, Object>> channels =
                fileChannelConfigMapper.selectByQuery(tid, null, null, null, null);
        List<Map<String, Object>> routings =
                alertRoutingConfigMapper.selectByQuery(tid, null, null, null, null, null);
        List<Map<String, Object>> pipelines =
                pipelineDefinitionMapper.selectByQuery(tid, null, null, null, null);
        List<Map<String, Object>> steps = collectPipelineSteps(pipelines);
        List<WorkflowDefinitionEntity> wfEntities = workflowDefinitionMapper.selectByQuery(
                new WorkflowDefinitionQuery(tid, null, null, null, null, null, null));
        List<Map<String, Object>> wfDefs = toWfDefRows(wfEntities);
        List<Map<String, Object>> wfNodes = collectWorkflowNodes(tid, wfEntities);
        List<Map<String, Object>> wfEdges = collectWorkflowEdges(tid, wfEntities);
        byte[] bytes = buildExportWorkbook(
                new PackageExportData(jobs, channels, routings, pipelines, steps, wfDefs, wfNodes, wfEdges));
        String fileName = "tenant-config-package-" + tid + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return excelResponse(fileName, bytes);
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadTemplate() {
        byte[] bytes = buildTemplateWorkbook();
        return excelResponse("tenant-config-package-template.xlsx", bytes);
    }

    @Override
    public TenantConfigPackageExcelUploadResponse upload(MultipartFile file) throws IOException {
        Guard.require(file != null && !file.isEmpty(), "file is required");
        String tenantId = tenantGuard.resolveTenant(null);
        String fileName = fileNameOrDefault(file.getOriginalFilename());
        PackageExcelSession session = parseWorkbook(file.getBytes(), tenantId, fileName);
        String token = importStore.save(session);
        return new TenantConfigPackageExcelUploadResponse(
                token, fileName,
                session.jobRows().size(),
                session.fileChannelRows().size(),
                session.alertRoutingRows().size(),
                session.pipelineRows().size(),
                session.pipelineStepRows().size(),
                session.workflowDefinitionRows().size(),
                session.workflowNodeRows().size(),
                session.workflowEdgeRows().size());
    }

    @Override
    public TenantConfigPackageExcelPreviewResponse preview(String uploadToken) {
        PackageExcelSession session = loadSession(uploadToken);
        PackageValidationResult result = validate(session);
        return toPreviewResponse(uploadToken, session.fileName(), result);
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
        PackageExcelSession session = loadSession(uploadToken);
        PackageValidationResult result = validate(session);
        byte[] bytes = buildPreviewWorkbook(session, result);
        return excelResponse(
                ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()),
                bytes);
    }

    @Override
    @Transactional
    public TenantConfigPackageExcelApplyResponse apply(
            String uploadToken, TenantConfigPackageExcelApplyRequest request) {
        PackageExcelSession session = loadSession(uploadToken);
        PackageValidationResult result = validate(session);
        if (result.totalInvalid() > 0) {
            throw new BizException(
                    ResultCode.INVALID_ARGUMENT, "excel contains invalid rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        ApplyContext ctx = new ApplyContext(
                session.tenantId(), metadata.operatorId(),
                request.getReason(), metadata.traceId());

        ApplyStats jobStats = applyJobs(result.validJobs(), ctx);
        ApplyStats channelStats = applyChannels(result.validChannels(), ctx);
        ApplyStats routingStats = applyRoutings(result.validRoutings(), ctx);
        ApplyStats pipelineStats = applyPipelines(result.validPipelines(), result.validSteps(), ctx);
        ApplyStats wfStats = applyWorkflows(result.validWfDefs(), result.validWfNodes(), result.validWfEdges(), ctx);

        importStore.remove(uploadToken);
        return new TenantConfigPackageExcelApplyResponse(
                uploadToken, session.tenantId(),
                jobStats.inserted(), jobStats.updated(),
                channelStats.inserted(), channelStats.updated(),
                routingStats.inserted(), routingStats.updated(),
                pipelineStats.inserted(), pipelineStats.updated(),
                wfStats.inserted(), wfStats.updated());
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    private PackageExcelSession parseWorkbook(byte[] bytes, String tenantId, String fileName) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            return new PackageExcelSession(
                    fileName, tenantId, Instant.now(),
                    parseSheet(wb, JOB_SHEET, JOB_COLUMNS, tenantId),
                    parseSheet(wb, CHANNEL_SHEET, CHANNEL_COLUMNS, tenantId),
                    parseSheet(wb, ROUTING_SHEET, ROUTING_COLUMNS, tenantId),
                    parseSheet(wb, PIPELINE_SHEET, PIPELINE_COLUMNS, tenantId),
                    parseSheet(wb, STEP_SHEET, STEP_COLUMNS, null),
                    parseSheet(wb, WF_DEF_SHEET, WF_DEF_COLUMNS, tenantId),
                    parseSheet(wb, WF_NODE_SHEET, WF_NODE_COLUMNS, tenantId),
                    parseSheet(wb, WF_EDGE_SHEET, WF_EDGE_COLUMNS, tenantId));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(
                    ResultCode.INVALID_ARGUMENT,
                    "failed to read excel workbook: " + e.getMessage());
        }
    }

    private List<Map<String, String>> parseSheet(
            Workbook wb, String sheetName, List<String> columns, String tenantId) {
        Sheet sheet = wb.getSheet(sheetName);
        Guard.require(sheet != null, "excel sheet missing: " + sheetName);
        DataFormatter fmt = new DataFormatter();
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        Guard.require(headerRow != null, "header row missing in sheet: " + sheetName);
        Map<String, Integer> headerIndex = buildHeaderIndex(headerRow, fmt);
        validateSheetHeaders(sheetName, headerIndex, Set.copyOf(columns));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowBlank(row, fmt)) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (String col : columns) {
                Integer colIdx = headerIndex.get(col);
                values.put(col, normalize(cellText(row, colIdx, fmt)));
            }
            if (tenantId != null && !StringUtils.hasText(values.get("tenant_id"))) {
                values.put("tenant_id", tenantId);
            }
            rows.add(values);
        }
        return rows;
    }

    // ── validation ────────────────────────────────────────────────────────────

    private PackageValidationResult validate(PackageExcelSession session) {
        String tid = session.tenantId();
        SheetResult jobs = validateJobRows(tid, session.jobRows());
        SheetResult channels = validateChannelRows(tid, session.fileChannelRows());
        SheetResult routings = validateRoutingRows(tid, session.alertRoutingRows());
        SheetResult pipelines = validatePipelineRows(tid, session.pipelineRows());
        SheetResult steps = validateStepRows(session.pipelineStepRows(), pipelines.validRows());
        SheetResult wfDefs = validateWfDefRows(tid, session.workflowDefinitionRows());
        SheetResult wfNodes = validateWfNodeRows(tid, session.workflowNodeRows(), wfDefs.validRows());
        SheetResult wfEdges = validateWfEdgeRows(tid, session.workflowEdgeRows(), wfDefs.validRows(), wfNodes.validRows());
        List<WorkbookIssue> crossIssues = validateCrossReferences(
                tid, jobs.validRows(), pipelines.validRows(),
                wfNodes.validRows(), session.pipelineRows());
        return new PackageValidationResult(jobs, channels, routings, pipelines, steps, wfDefs, wfNodes, wfEdges, crossIssues);
    }

    private SheetResult validateJobRows(String tenantId, List<Map<String, String>> rows) {
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String jobCode = normalize(row.get("job_code"));
            if (!hasText(jobCode)) ri.add("job_code is required");
            if (!hasText(normalize(row.get("job_name")))) ri.add("job_name is required");
            String jobType = normalizeEnum(row.get("job_type"));
            if (!hasText(jobType)) ri.add("job_type is required");
            else if (!JOB_TYPES.contains(jobType)) ri.add("job_type must be one of " + JOB_TYPES);
            String scheduleType = normalizeEnum(row.get("schedule_type"));
            if (!hasText(scheduleType)) ri.add("schedule_type is required");
            else if (!SCHEDULE_TYPES.contains(scheduleType)) ri.add("schedule_type must be one of " + SCHEDULE_TYPES);
            String retryPolicy = normalizeEnum(row.get("retry_policy"));
            if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
                ri.add("retry_policy must be one of " + RETRY_POLICIES);
            String shardStrategy = normalizeEnum(row.get("shard_strategy"));
            if (hasText(shardStrategy) && !SHARD_STRATEGIES.contains(shardStrategy))
                ri.add("shard_strategy must be one of " + SHARD_STRATEGIES);
            String paramSchema = row.get("param_schema");
            if (hasText(paramSchema)) {
                try { JsonUtils.fromJson(paramSchema, Object.class); }
                catch (Exception e) { ri.add("param_schema must be valid JSON"); }
            }
            if (hasText(jobCode) && !seen.add(tenantId + "#" + jobCode))
                ri.add("duplicate job_code in excel: " + jobCode);
            addIssues(ri, JOB_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(JOB_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validateChannelRows(String tenantId, List<Map<String, String>> rows) {
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String code = normalize(row.get("channel_code"));
            if (!hasText(code)) ri.add("channel_code is required");
            if (!hasText(normalize(row.get("channel_name")))) ri.add("channel_name is required");
            String channelType = normalizeEnum(row.get("channel_type"));
            if (!hasText(channelType)) ri.add("channel_type is required");
            else if (!CHANNEL_TYPES.contains(channelType)) ri.add("channel_type must be one of " + CHANNEL_TYPES);
            String authType = normalizeEnum(row.get("auth_type"));
            if (!hasText(authType)) ri.add("auth_type is required");
            else if (!AUTH_TYPES.contains(authType)) ri.add("auth_type must be one of " + AUTH_TYPES);
            String receiptPolicy = normalizeEnum(row.get("receipt_policy"));
            if (!hasText(receiptPolicy)) ri.add("receipt_policy is required");
            else if (!RECEIPT_POLICIES.contains(receiptPolicy)) ri.add("receipt_policy must be one of " + RECEIPT_POLICIES);
            String configJson = row.get("config_json");
            if (!hasText(configJson)) ri.add("config_json is required");
            else {
                try { JsonUtils.fromJson(configJson, Object.class); }
                catch (Exception e) { ri.add("config_json must be valid JSON"); }
            }
            if (hasText(code) && !seen.add(tenantId + "#" + code))
                ri.add("duplicate channel_code in excel: " + code);
            addIssues(ri, CHANNEL_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(CHANNEL_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validateRoutingRows(String tenantId, List<Map<String, String>> rows) {
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String code = normalize(row.get("route_code"));
            if (!hasText(code)) ri.add("route_code is required");
            if (!hasText(normalize(row.get("route_name")))) ri.add("route_name is required");
            if (!hasText(normalize(row.get("team")))) ri.add("team is required");
            if (!hasText(normalize(row.get("alert_group")))) ri.add("alert_group is required");
            String severity = normalizeEnum(row.get("severity"));
            if (!hasText(severity)) ri.add("severity is required");
            else if (!SEVERITIES.contains(severity)) ri.add("severity must be one of " + SEVERITIES);
            if (!hasText(normalize(row.get("receiver")))) ri.add("receiver is required");
            if (hasText(code) && !seen.add(tenantId + "#" + code))
                ri.add("duplicate route_code in excel: " + code);
            addIssues(ri, ROUTING_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(ROUTING_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validatePipelineRows(String tenantId, List<Map<String, String>> rows) {
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String jobCode = normalize(row.get("job_code"));
            String version = normalize(row.get("version"));
            if (!hasText(jobCode)) ri.add("job_code is required");
            if (!hasText(normalize(row.get("pipeline_name")))) ri.add("pipeline_name is required");
            String pType = normalizeEnum(row.get("pipeline_type"));
            if (!hasText(pType)) ri.add("pipeline_type is required");
            else if (!PIPELINE_TYPES.contains(pType)) ri.add("pipeline_type must be one of " + PIPELINE_TYPES);
            if (!hasText(version)) ri.add("version is required");
            else { try { Integer.parseInt(version); } catch (NumberFormatException e) { ri.add("version must be integer"); } }
            if (hasText(jobCode) && hasText(version) && !seen.add(tenantId + "#" + jobCode + ":" + version))
                ri.add("duplicate pipeline key (job_code + version): " + jobCode + ":" + version);
            addIssues(ri, PIPELINE_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(PIPELINE_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validateStepRows(
            List<Map<String, String>> rows, List<Map<String, String>> validPipelineRows) {
        Set<String> pipelineKeys = validPipelineRows.stream()
                .map(r -> normalize(r.get("job_code")) + ":" + normalize(r.get("version")))
                .collect(Collectors.toSet());
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String jobCode = normalize(row.get("job_code"));
            String version = normalize(row.get("version"));
            String stepCode = normalize(row.get("step_code"));
            if (!hasText(jobCode)) ri.add("job_code is required");
            if (!hasText(version)) ri.add("version is required");
            if (!hasText(stepCode)) ri.add("step_code is required");
            if (!hasText(normalize(row.get("step_name")))) ri.add("step_name is required");
            String stageCode = normalizeEnum(row.get("stage_code"));
            if (!hasText(stageCode)) ri.add("stage_code is required");
            else if (!STAGE_CODES.contains(stageCode)) ri.add("stage_code must be one of " + STAGE_CODES);
            String retryPolicy = normalizeEnum(row.get("retry_policy"));
            if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
                ri.add("retry_policy must be one of " + RETRY_POLICIES);
            String pipelineKey = jobCode + ":" + version;
            if (hasText(jobCode) && hasText(version) && !pipelineKeys.contains(pipelineKey))
                ri.add("no matching pipeline for job_code + version: " + pipelineKey);
            if (hasText(jobCode) && hasText(version) && hasText(stepCode)
                    && !seen.add(pipelineKey + "#" + stepCode))
                ri.add("duplicate step_code in pipeline: " + stepCode);
            addIssues(ri, STEP_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(STEP_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validateWfDefRows(String tenantId, List<Map<String, String>> rows) {
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String wfCode = normalize(row.get("workflow_code"));
            String version = normalize(row.get("version"));
            if (!hasText(wfCode)) ri.add("workflow_code is required");
            if (!hasText(normalize(row.get("workflow_name")))) ri.add("workflow_name is required");
            String wfType = normalizeEnum(row.get("workflow_type"));
            if (!hasText(wfType)) ri.add("workflow_type is required");
            else if (!WORKFLOW_TYPES.contains(wfType)) ri.add("workflow_type must be one of " + WORKFLOW_TYPES);
            if (!hasText(version)) ri.add("version is required");
            else { try { Integer.parseInt(version); } catch (NumberFormatException e) { ri.add("version must be integer"); } }
            if (hasText(wfCode) && hasText(version) && !seen.add(tenantId + "#" + wfCode + ":" + version))
                ri.add("duplicate workflow definition: " + wfCode + ":" + version);
            addIssues(ri, WF_DEF_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(WF_DEF_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validateWfNodeRows(
            String tenantId,
            List<Map<String, String>> rows,
            List<Map<String, String>> validWfDefs) {
        Set<String> wfKeys = validWfDefs.stream()
                .map(r -> normalize(r.get("workflow_code")) + ":" + normalize(r.get("version")))
                .collect(Collectors.toSet());
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String wfCode = normalize(row.get("workflow_code"));
            String wfVersion = normalize(row.get("workflow_version"));
            String nodeCode = normalize(row.get("node_code"));
            if (!hasText(wfCode)) ri.add("workflow_code is required");
            if (!hasText(wfVersion)) ri.add("workflow_version is required");
            if (!hasText(nodeCode)) ri.add("node_code is required");
            if (!hasText(normalize(row.get("node_name")))) ri.add("node_name is required");
            String nodeType = normalizeEnum(row.get("node_type"));
            if (!hasText(nodeType)) ri.add("node_type is required");
            else if (!NODE_TYPES.contains(nodeType)) ri.add("node_type must be one of " + NODE_TYPES);
            String retryPolicy = normalizeEnum(row.get("retry_policy"));
            if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
                ri.add("retry_policy must be one of " + RETRY_POLICIES);
            String nodeParams = row.get("node_params");
            if (hasText(nodeParams)) {
                try { JsonUtils.fromJson(nodeParams, Object.class); }
                catch (Exception e) { ri.add("node_params must be valid JSON"); }
            }
            String wfKey = wfCode + ":" + wfVersion;
            if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey))
                ri.add("workflow node references missing definition: " + wfKey);
            if (hasText(wfCode) && hasText(wfVersion) && hasText(nodeCode)
                    && !seen.add(wfKey + "#" + nodeCode))
                ri.add("duplicate node_code in workflow: " + nodeCode);
            addIssues(ri, WF_NODE_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(WF_NODE_SHEET, rows.size(), valid, issues);
    }

    private SheetResult validateWfEdgeRows(
            String tenantId,
            List<Map<String, String>> rows,
            List<Map<String, String>> validWfDefs,
            List<Map<String, String>> validNodes) {
        Set<String> wfKeys = validWfDefs.stream()
                .map(r -> normalize(r.get("workflow_code")) + ":" + normalize(r.get("version")))
                .collect(Collectors.toSet());
        Set<String> nodeKeys = validNodes.stream()
                .map(r -> normalize(r.get("workflow_code")) + ":" + normalize(r.get("workflow_version")) + "#" + normalize(r.get("node_code")))
                .collect(Collectors.toSet());
        List<WorkbookIssue> issues = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        int rowNo = 2;
        for (Map<String, String> row : rows) {
            List<String> ri = new ArrayList<>();
            String wfCode = normalize(row.get("workflow_code"));
            String wfVersion = normalize(row.get("workflow_version"));
            String fromNode = normalize(row.get("from_node_code"));
            String toNode = normalize(row.get("to_node_code"));
            if (!hasText(wfCode)) ri.add("workflow_code is required");
            if (!hasText(wfVersion)) ri.add("workflow_version is required");
            if (!hasText(fromNode)) ri.add("from_node_code is required");
            if (!hasText(toNode)) ri.add("to_node_code is required");
            String edgeType = normalizeEnum(row.get("edge_type"));
            if (!hasText(edgeType)) ri.add("edge_type is required");
            else if (!EDGE_TYPES.contains(edgeType)) ri.add("edge_type must be one of " + EDGE_TYPES);
            String wfKey = wfCode + ":" + wfVersion;
            if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey))
                ri.add("workflow edge references missing definition: " + wfKey);
            if (hasText(wfCode) && hasText(wfVersion) && hasText(fromNode)
                    && !nodeKeys.contains(wfKey + "#" + fromNode))
                ri.add("from_node_code references unknown node: " + fromNode);
            if (hasText(wfCode) && hasText(wfVersion) && hasText(toNode)
                    && !nodeKeys.contains(wfKey + "#" + toNode))
                ri.add("to_node_code references unknown node: " + toNode);
            addIssues(ri, WF_EDGE_SHEET, rowNo, issues);
            if (ri.isEmpty()) valid.add(row);
            rowNo++;
        }
        return new SheetResult(WF_EDGE_SHEET, rows.size(), valid, issues);
    }

    /** 跨 sheet 依赖校验：pipeline.job_code / wf_node.related_job_code / wf_node.related_pipeline_code。 */
    private List<WorkbookIssue> validateCrossReferences(
            String tenantId,
            List<Map<String, String>> validJobs,
            List<Map<String, String>> validPipelines,
            List<Map<String, String>> validWfNodes,
            List<Map<String, String>> allPipelineRows) {
        Set<String> jobCodesInExcel = validJobs.stream()
                .map(r -> normalize(r.get("job_code"))).filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Set<String> pipelineJobCodesInExcel = validPipelines.stream()
                .map(r -> normalize(r.get("job_code"))).filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        List<WorkbookIssue> issues = new ArrayList<>();

        // pipeline.job_code → job_definition
        int rowNo = 2;
        for (Map<String, String> row : allPipelineRows) {
            String jobCode = normalize(row.get("job_code"));
            if (hasText(jobCode) && !jobCodesInExcel.contains(jobCode)
                    && jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode) == null) {
                issues.add(new WorkbookIssue(PIPELINE_SHEET, rowNo, "job_code",
                        "job_code references unknown job definition: " + jobCode));
            }
            rowNo++;
        }

        // wf_node.related_job_code / related_pipeline_code
        rowNo = 2;
        for (Map<String, String> row : validWfNodes) {
            String relatedJob = normalize(row.get("related_job_code"));
            if (hasText(relatedJob) && !jobCodesInExcel.contains(relatedJob)
                    && jobDefinitionMapper.selectByUniqueKey(tenantId, relatedJob) == null) {
                issues.add(new WorkbookIssue(WF_NODE_SHEET, rowNo, "related_job_code",
                        "related_job_code references unknown job definition: " + relatedJob));
            }
            String relatedPipeline = normalize(row.get("related_pipeline_code"));
            if (hasText(relatedPipeline) && !pipelineJobCodesInExcel.contains(relatedPipeline)) {
                List<Map<String, Object>> found = pipelineDefinitionMapper.selectByQuery(
                        tenantId, relatedPipeline, null, null, new PageRequest(1, 1));
                if (found == null || found.isEmpty()) {
                    issues.add(new WorkbookIssue(WF_NODE_SHEET, rowNo, "related_pipeline_code",
                            "related_pipeline_code references unknown pipeline: " + relatedPipeline));
                }
            }
            rowNo++;
        }
        return issues;
    }

    // ── apply ─────────────────────────────────────────────────────────────────

    private ApplyStats applyJobs(List<Map<String, String>> rows, ApplyContext ctx) {
        int inserted = 0, updated = 0;
        for (Map<String, String> row : rows) {
            String jobCode = normalize(row.get("job_code"));
            JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode);
            if (existing == null) {
                JobDefinitionEntity entity = new JobDefinitionEntity();
                entity.setTenantId(ctx.tenantId());
                entity.setJobCode(jobCode);
                entity.setJobName(normalize(row.get("job_name")));
                entity.setJobType(normalizeEnum(row.get("job_type")));
                entity.setBizType(normalize(row.get("biz_type")));
                entity.setQueueCode(normalize(row.get("queue_code")));
                entity.setWorkerGroup(normalize(row.get("worker_group")));
                entity.setScheduleType(normalizeEnum(row.get("schedule_type")));
                entity.setScheduleExpr(normalize(row.get("schedule_expr")));
                entity.setCalendarCode(normalize(row.get("calendar_code")));
                entity.setWindowCode(normalize(row.get("window_code")));
                entity.setRetryPolicy(normalizeEnum(row.get("retry_policy")));
                entity.setRetryMaxCount(parseInteger(row.get("retry_max_count")));
                entity.setTimeoutSeconds(parseInteger(row.get("timeout_seconds")));
                entity.setShardStrategy(normalizeEnum(row.get("shard_strategy")));
                entity.setExecutionHandler(normalize(row.get("execution_handler")));
                entity.setParamSchema(normalize(row.get("param_schema")));
                entity.setDefaultParams(normalize(row.get("default_params")));
                entity.setEnabled(parseBoolean(row.get("enabled"), true));
                entity.setDescription(normalize(row.get("description")));
                entity.setCreatedBy(safeOp(ctx.operatorId()));
                entity.setUpdatedBy(safeOp(ctx.operatorId()));
                jobDefinitionMapper.insert(entity);
                inserted++;
            } else {
                JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
                param.setTenantId(ctx.tenantId());
                param.setJobCode(jobCode);
                param.setJobName(normalize(row.get("job_name")));
                param.setQueueCode(normalize(row.get("queue_code")));
                param.setWorkerGroup(normalize(row.get("worker_group")));
                param.setScheduleExpr(normalize(row.get("schedule_expr")));
                param.setCalendarCode(normalize(row.get("calendar_code")));
                param.setWindowCode(normalize(row.get("window_code")));
                param.setRetryPolicy(normalizeEnum(row.get("retry_policy")));
                param.setRetryMaxCount(parseInteger(row.get("retry_max_count")));
                param.setTimeoutSeconds(parseInteger(row.get("timeout_seconds")));
                param.setShardStrategy(normalizeEnum(row.get("shard_strategy")));
                param.setEnabled(parseBoolean(row.get("enabled"), true));
                param.setDescription(normalize(row.get("description")));
                param.setUpdatedBy(safeOp(ctx.operatorId()));
                jobDefinitionMapper.updateJobDefinitionMaintenance(param);
                updated++;
            }
        }
        return new ApplyStats(inserted, updated);
    }

    private ApplyStats applyChannels(List<Map<String, String>> rows, ApplyContext ctx) {
        int inserted = 0, updated = 0;
        for (Map<String, String> row : rows) {
            String code = normalize(row.get("channel_code"));
            Map<String, Object> existing = fileChannelConfigMapper.selectByUniqueKey(ctx.tenantId(), code);
            FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
            param.setTenantId(ctx.tenantId());
            param.setChannelCode(code);
            param.setChannelName(normalize(row.get("channel_name")));
            param.setChannelType(normalizeEnum(row.get("channel_type")));
            param.setTargetEndpoint(normalize(row.get("target_endpoint")));
            param.setAuthType(normalizeEnum(row.get("auth_type")));
            param.setConfigJson(row.get("config_json"));
            param.setReceiptPolicy(normalizeEnum(row.get("receipt_policy")));
            param.setTimeoutSeconds(parseInteger(row.get("timeout_seconds")));
            param.setEnabled(parseBoolean(row.get("enabled"), true));
            param.setCreatedBy(safeOp(ctx.operatorId()));
            param.setUpdatedBy(safeOp(ctx.operatorId()));
            fileChannelConfigMapper.upsertFileChannelConfig(param);
            if (existing == null || existing.isEmpty()) inserted++; else updated++;
        }
        return new ApplyStats(inserted, updated);
    }

    private ApplyStats applyRoutings(List<Map<String, String>> rows, ApplyContext ctx) {
        int inserted = 0, updated = 0;
        for (Map<String, String> row : rows) {
            String code = normalize(row.get("route_code"));
            Map<String, Object> existing = alertRoutingConfigMapper.selectByUniqueKey(ctx.tenantId(), code);
            AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
            param.setTenantId(ctx.tenantId());
            param.setRouteCode(code);
            param.setRouteName(normalize(row.get("route_name")));
            param.setTeam(normalize(row.get("team")));
            param.setAlertGroup(normalize(row.get("alert_group")));
            param.setSeverity(normalizeEnum(row.get("severity")));
            param.setReceiver(normalize(row.get("receiver")));
            param.setGroupBy(normalize(row.get("group_by")));
            param.setGroupWaitSeconds(parseInteger(row.get("group_wait_seconds")));
            param.setGroupIntervalSeconds(parseInteger(row.get("group_interval_seconds")));
            param.setRepeatIntervalSeconds(parseInteger(row.get("repeat_interval_seconds")));
            param.setEnabled(parseBoolean(row.get("enabled"), true));
            param.setDescription(normalize(row.get("description")));
            param.setCreatedBy(safeOp(ctx.operatorId()));
            param.setUpdatedBy(safeOp(ctx.operatorId()));
            alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
            if (existing == null || existing.isEmpty()) inserted++; else updated++;
        }
        return new ApplyStats(inserted, updated);
    }

    private ApplyStats applyPipelines(
            List<Map<String, String>> pipelineRows,
            List<Map<String, String>> stepRows,
            ApplyContext ctx) {
        Map<String, List<Map<String, String>>> stepsByKey = stepRows.stream()
                .collect(Collectors.groupingBy(
                        r -> normalize(r.get("job_code")) + ":" + normalize(r.get("version"))));
        int inserted = 0, updated = 0;
        for (Map<String, String> row : pipelineRows) {
            String jobCode = normalize(row.get("job_code"));
            int version = Integer.parseInt(normalize(row.get("version")));
            Map<String, Object> existing =
                    pipelineDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode, version);
            Long pipelineId;
            if (existing == null || existing.isEmpty()) {
                Map<String, Object> params = buildPipelineInsertParams(row, ctx);
                pipelineDefinitionMapper.insert(params);
                pipelineId = ((Number) params.get("id")).longValue();
                inserted++;
            } else {
                pipelineId = ((Number) existing.get("id")).longValue();
                pipelineDefinitionMapper.update(buildPipelineUpdateParams(pipelineId, row, ctx));
                updated++;
            }
            pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(pipelineId);
            for (Map<String, String> step : stepsByKey.getOrDefault(jobCode + ":" + version, List.of())) {
                pipelineStepDefinitionMapper.insert(buildStepInsertParams(pipelineId, step));
            }
        }
        return new ApplyStats(inserted, updated);
    }

    private ApplyStats applyWorkflows(
            List<Map<String, String>> defRows,
            List<Map<String, String>> nodeRows,
            List<Map<String, String>> edgeRows,
            ApplyContext ctx) {
        Map<String, List<Map<String, String>>> nodesByWf = nodeRows.stream()
                .collect(Collectors.groupingBy(
                        r -> normalize(r.get("workflow_code")) + ":" + normalize(r.get("workflow_version"))));
        Map<String, List<Map<String, String>>> edgesByWf = edgeRows.stream()
                .collect(Collectors.groupingBy(
                        r -> normalize(r.get("workflow_code")) + ":" + normalize(r.get("workflow_version"))));
        int inserted = 0, updated = 0;
        for (Map<String, String> row : defRows) {
            String wfCode = normalize(row.get("workflow_code"));
            int version = Integer.parseInt(normalize(row.get("version")));
            WorkflowDefinitionEntity existing =
                    workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
            WorkflowDefinitionUpsertParam defParam = new WorkflowDefinitionUpsertParam();
            defParam.setTenantId(ctx.tenantId());
            defParam.setWorkflowCode(wfCode);
            defParam.setWorkflowName(normalize(row.get("workflow_name")));
            defParam.setWorkflowType(normalizeEnum(row.get("workflow_type")));
            defParam.setVersion(version);
            defParam.setEnabled(parseBoolean(row.get("enabled"), true));
            defParam.setDescription(normalize(row.get("description")));
            defParam.setCreatedBy(safeOp(ctx.operatorId()));
            defParam.setUpdatedBy(safeOp(ctx.operatorId()));
            workflowDefinitionMapper.upsertWorkflowDefinition(defParam);
            if (existing == null) inserted++; else updated++;

            WorkflowDefinitionEntity saved =
                    workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
            if (saved == null || saved.getId() == null) continue;
            Long defId = saved.getId();
            String wfKey = wfCode + ":" + version;
            applyWfNodes(defId, nodesByWf.getOrDefault(wfKey, List.of()));
            applyWfEdges(defId, edgesByWf.getOrDefault(wfKey, List.of()));
        }
        return new ApplyStats(inserted, updated);
    }

    private void applyWfNodes(Long defId, List<Map<String, String>> nodes) {
        for (Map<String, String> node : nodes) {
            WorkflowNodeUpsertParam p = new WorkflowNodeUpsertParam();
            p.setWorkflowDefinitionId(defId);
            p.setNodeCode(normalize(node.get("node_code")));
            p.setNodeName(normalize(node.get("node_name")));
            p.setNodeType(normalizeEnum(node.get("node_type")));
            p.setRelatedJobCode(normalize(node.get("related_job_code")));
            p.setRelatedPipelineCode(normalize(node.get("related_pipeline_code")));
            p.setWorkerGroup(normalize(node.get("worker_group")));
            p.setWindowCode(normalize(node.get("window_code")));
            p.setNodeOrder(parseInteger(node.get("node_order")));
            p.setRetryPolicy(normalizeEnum(node.get("retry_policy")));
            p.setRetryMaxCount(parseInteger(node.get("retry_max_count")));
            p.setTimeoutSeconds(parseInteger(node.get("timeout_seconds")));
            p.setNodeParams(normalize(node.get("node_params")));
            p.setEnabled(parseBoolean(node.get("enabled"), true));
            workflowNodeMapper.upsertWorkflowNode(p);
        }
    }

    private void applyWfEdges(Long defId, List<Map<String, String>> edges) {
        for (Map<String, String> edge : edges) {
            WorkflowEdgeUpsertParam p = new WorkflowEdgeUpsertParam();
            p.setWorkflowDefinitionId(defId);
            p.setFromNodeCode(normalize(edge.get("from_node_code")));
            p.setToNodeCode(normalize(edge.get("to_node_code")));
            p.setEdgeType(normalizeEnum(edge.get("edge_type")));
            p.setConditionExpr(normalize(edge.get("condition_expr")));
            p.setEnabled(parseBoolean(edge.get("enabled"), true));
            workflowEdgeMapper.upsertWorkflowEdge(p);
        }
    }

    // ── pipeline param builders ───────────────────────────────────────────────

    private Map<String, Object> buildPipelineInsertParams(Map<String, String> row, ApplyContext ctx) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenantId", ctx.tenantId());
        p.put("jobCode", normalize(row.get("job_code")));
        p.put("pipelineName", normalize(row.get("pipeline_name")));
        p.put("pipelineType", normalizeEnum(row.get("pipeline_type")));
        p.put("bizType", normalize(row.get("biz_type")));
        p.put("workerGroup", normalize(row.get("worker_group")));
        p.put("version", parseInteger(row.get("version")));
        p.put("enabled", parseBoolean(row.get("enabled"), true));
        p.put("description", normalize(row.get("description")));
        p.put("createdBy", safeOp(ctx.operatorId()));
        p.put("updatedBy", safeOp(ctx.operatorId()));
        p.put("id", null);
        return p;
    }

    private Map<String, Object> buildPipelineUpdateParams(Long id, Map<String, String> row, ApplyContext ctx) {
        Map<String, Object> p = buildPipelineInsertParams(row, ctx);
        p.put("id", id);
        return p;
    }

    private Map<String, Object> buildStepInsertParams(Long pipelineId, Map<String, String> step) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pipelineDefinitionId", pipelineId);
        p.put("stepCode", normalize(step.get("step_code")));
        p.put("stepName", normalize(step.get("step_name")));
        p.put("stageCode", normalizeEnum(step.get("stage_code")));
        p.put("stepOrder", parseInteger(step.get("step_order")));
        p.put("implCode", normalize(step.get("impl_code")));
        p.put("stepParams", normalize(step.get("step_params")));
        p.put("timeoutSeconds", parseInteger(step.get("timeout_seconds")));
        p.put("retryPolicy", normalizeEnum(step.get("retry_policy")));
        p.put("retryMaxCount", parseInteger(step.get("retry_max_count")));
        p.put("enabled", parseBoolean(step.get("enabled"), true));
        return p;
    }

    // ── export helpers ────────────────────────────────────────────────────────

    private List<Map<String, Object>> toJobRows(List<JobDefinitionEntity> entities) {
        return entities.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenant_id", e.getTenantId());
            m.put("job_code", e.getJobCode());
            m.put("job_name", e.getJobName());
            m.put("job_type", e.getJobType());
            m.put("biz_type", e.getBizType());
            m.put("queue_code", e.getQueueCode());
            m.put("worker_group", e.getWorkerGroup());
            m.put("schedule_type", e.getScheduleType());
            m.put("schedule_expr", e.getScheduleExpr());
            m.put("calendar_code", e.getCalendarCode());
            m.put("window_code", e.getWindowCode());
            m.put("retry_policy", e.getRetryPolicy());
            m.put("retry_max_count", e.getRetryMaxCount());
            m.put("timeout_seconds", e.getTimeoutSeconds());
            m.put("shard_strategy", e.getShardStrategy());
            m.put("execution_handler", e.getExecutionHandler());
            m.put("param_schema", e.getParamSchema());
            m.put("default_params", e.getDefaultParams());
            m.put("enabled", e.getEnabled());
            m.put("description", e.getDescription());
            return m;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> collectPipelineSteps(List<Map<String, Object>> pipelines) {
        List<Map<String, Object>> allSteps = new ArrayList<>();
        for (Map<String, Object> pipeline : pipelines) {
            Long pipelineId = ((Number) pipeline.get("id")).longValue();
            String jobCode = String.valueOf(pipeline.get("job_code"));
            String version = String.valueOf(pipeline.get("version"));
            for (Map<String, Object> step :
                    pipelineStepDefinitionMapper.selectByPipelineDefinitionId(pipelineId)) {
                Map<String, Object> enriched = new LinkedHashMap<>(step);
                enriched.put("job_code", jobCode);
                enriched.put("version", version);
                allSteps.add(enriched);
            }
        }
        return allSteps;
    }

    private List<Map<String, Object>> toWfDefRows(List<WorkflowDefinitionEntity> entities) {
        return entities.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenant_id", e.getTenantId());
            m.put("workflow_code", e.getWorkflowCode());
            m.put("workflow_name", e.getWorkflowName());
            m.put("workflow_type", e.getWorkflowType());
            m.put("version", e.getVersion());
            m.put("enabled", e.getEnabled());
            m.put("description", e.getDescription());
            return m;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> collectWorkflowNodes(
            String tenantId, List<WorkflowDefinitionEntity> defs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkflowDefinitionEntity def : defs) {
            List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(
                    new WorkflowNodeQuery(tenantId, def.getId(), def.getWorkflowCode(),
                            null, null, null, null));
            for (WorkflowNodeEntity node : nodes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tenant_id", tenantId);
                m.put("workflow_code", def.getWorkflowCode());
                m.put("workflow_version", def.getVersion());
                m.put("node_code", node.getNodeCode());
                m.put("node_name", node.getNodeName());
                m.put("node_type", node.getNodeType());
                m.put("related_job_code", node.getRelatedJobCode());
                m.put("related_pipeline_code", node.getRelatedPipelineCode());
                m.put("worker_group", node.getWorkerGroup());
                m.put("window_code", node.getWindowCode());
                m.put("node_order", node.getNodeOrder());
                m.put("retry_policy", node.getRetryPolicy());
                m.put("retry_max_count", node.getRetryMaxCount());
                m.put("timeout_seconds", node.getTimeoutSeconds());
                m.put("node_params", node.getNodeParams());
                m.put("enabled", node.getEnabled());
                result.add(m);
            }
        }
        return result;
    }

    private List<Map<String, Object>> collectWorkflowEdges(
            String tenantId, List<WorkflowDefinitionEntity> defs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkflowDefinitionEntity def : defs) {
            List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectByQuery(
                    new WorkflowEdgeQuery(tenantId, def.getId(), def.getWorkflowCode(),
                            null, null, null, null, null));
            for (WorkflowEdgeEntity edge : edges) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tenant_id", tenantId);
                m.put("workflow_code", def.getWorkflowCode());
                m.put("workflow_version", def.getVersion());
                m.put("from_node_code", edge.getFromNodeCode());
                m.put("to_node_code", edge.getToNodeCode());
                m.put("edge_type", edge.getEdgeType());
                m.put("condition_expr", edge.getConditionExpr());
                m.put("enabled", edge.getEnabled());
                result.add(m);
            }
        }
        return result;
    }

    private byte[] buildExportWorkbook(PackageExportData d) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeDataSheet(wb, JOB_SHEET, JOB_COLUMNS, buildJobGuides(), d.jobs(), this::applyJobValidations);
            writeDataSheet(wb, CHANNEL_SHEET, CHANNEL_COLUMNS, buildChannelGuides(), d.channels(), this::applyChannelValidations);
            writeDataSheet(wb, ROUTING_SHEET, ROUTING_COLUMNS, buildRoutingGuides(), d.routings(), this::applyRoutingValidations);
            writeDataSheet(wb, PIPELINE_SHEET, PIPELINE_COLUMNS, buildPipelineGuides(), d.pipelines(), this::applyPipelineValidations);
            writeDataSheet(wb, STEP_SHEET, STEP_COLUMNS, buildStepGuides(), d.steps(), this::applyStepValidations);
            writeDataSheet(wb, WF_DEF_SHEET, WF_DEF_COLUMNS, buildWfDefGuides(), d.wfDefs(), this::applyWfDefValidations);
            writeDataSheet(wb, WF_NODE_SHEET, WF_NODE_COLUMNS, buildWfNodeGuides(), d.wfNodes(), this::applyWfNodeValidations);
            writeDataSheet(wb, WF_EDGE_SHEET, WF_EDGE_COLUMNS, buildWfEdgeGuides(), d.wfEdges(), this::applyWfEdgeValidations);
            createReadmeSheet(wb);
            createFieldGuideSheet(wb);
            ConsoleExcelStyles.createValidationSheet(wb);
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate export workbook");
        }
    }

    // ── workbook generation ───────────────────────────────────────────────────

    private byte[] buildTemplateWorkbook() {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeDataSheet(wb, JOB_SHEET, JOB_COLUMNS, buildJobGuides(), List.of(), this::applyJobValidations);
            writeDataSheet(wb, CHANNEL_SHEET, CHANNEL_COLUMNS, buildChannelGuides(), List.of(), this::applyChannelValidations);
            writeDataSheet(wb, ROUTING_SHEET, ROUTING_COLUMNS, buildRoutingGuides(), List.of(), this::applyRoutingValidations);
            writeDataSheet(wb, PIPELINE_SHEET, PIPELINE_COLUMNS, buildPipelineGuides(), List.of(), this::applyPipelineValidations);
            writeDataSheet(wb, STEP_SHEET, STEP_COLUMNS, buildStepGuides(), List.of(), this::applyStepValidations);
            writeDataSheet(wb, WF_DEF_SHEET, WF_DEF_COLUMNS, buildWfDefGuides(), List.of(), this::applyWfDefValidations);
            writeDataSheet(wb, WF_NODE_SHEET, WF_NODE_COLUMNS, buildWfNodeGuides(), List.of(), this::applyWfNodeValidations);
            writeDataSheet(wb, WF_EDGE_SHEET, WF_EDGE_COLUMNS, buildWfEdgeGuides(), List.of(), this::applyWfEdgeValidations);
            createReadmeSheet(wb);
            createFieldGuideSheet(wb);
            ConsoleExcelStyles.createValidationSheet(wb);
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate template workbook");
        }
    }

    private byte[] buildPreviewWorkbook(PackageExcelSession session, PackageValidationResult result) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writePreviewSheet(wb, JOB_SHEET, JOB_COLUMNS, session.jobRows(), result.jobs().issues(), this::applyJobValidations);
            writePreviewSheet(wb, CHANNEL_SHEET, CHANNEL_COLUMNS, session.fileChannelRows(), result.channels().issues(), this::applyChannelValidations);
            writePreviewSheet(wb, ROUTING_SHEET, ROUTING_COLUMNS, session.alertRoutingRows(), result.routings().issues(), this::applyRoutingValidations);
            writePreviewSheet(wb, PIPELINE_SHEET, PIPELINE_COLUMNS, session.pipelineRows(), result.pipelines().issues(), this::applyPipelineValidations);
            writePreviewSheet(wb, STEP_SHEET, STEP_COLUMNS, session.pipelineStepRows(), result.steps().issues(), this::applyStepValidations);
            writePreviewSheet(wb, WF_DEF_SHEET, WF_DEF_COLUMNS, session.workflowDefinitionRows(), result.wfDefs().issues(), this::applyWfDefValidations);
            writePreviewSheet(wb, WF_NODE_SHEET, WF_NODE_COLUMNS, session.workflowNodeRows(), result.wfNodes().issues(), this::applyWfNodeValidations);
            writePreviewSheet(wb, WF_EDGE_SHEET, WF_EDGE_COLUMNS, session.workflowEdgeRows(), result.wfEdges().issues(), this::applyWfEdgeValidations);
            ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(wb, result.allIssues());
            return ConsoleExcelPreviewWorkbookSupport.toBytes(wb);
        } catch (IOException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate preview workbook");
        }
    }

    private void writeDataSheet(
            Workbook wb,
            String sheetName,
            List<String> columns,
            Map<String, ConsoleExcelStyles.ColumnGuide> guides,
            List<Map<String, Object>> dataRows,
            java.util.function.Consumer<Sheet> validationApplier) {
        Sheet sheet = wb.createSheet(sheetName);
        sheet.createFreezePane(0, 1);
        writeTemplateHeaders(sheet, columns, guides, wb);
        int idx = 1;
        for (Map<String, Object> row : dataRows) {
            Row dataRow = sheet.createRow(idx++);
            for (int c = 0; c < columns.size(); c++) {
                Object val = row.get(columns.get(c));
                dataRow.createCell(c).setCellValue(val == null ? "" : String.valueOf(val));
            }
        }
        validationApplier.accept(sheet);
        setWidths(sheet, columns);
    }

    private void writePreviewSheet(
            Workbook wb,
            String sheetName,
            List<String> columns,
            List<Map<String, String>> dataRows,
            List<WorkbookIssue> sheetIssues,
            java.util.function.Consumer<Sheet> validationApplier) {
        Sheet sheet = wb.createSheet(sheetName);
        sheet.createFreezePane(0, 1);
        CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
        writeHeaders(sheet, columns, headerStyle);
        int idx = 1;
        for (Map<String, String> row : dataRows) {
            Row dataRow = sheet.createRow(idx++);
            for (int c = 0; c < columns.size(); c++) {
                String val = row.get(columns.get(c));
                dataRow.createCell(c).setCellValue(val == null ? "" : val);
            }
        }
        validationApplier.accept(sheet);
        ConsoleExcelPreviewWorkbookSupport.addIssueComments(sheet, columns, sheetIssues, 0);
        setWidths(sheet, columns);
    }

    private void createReadmeSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("README");
        sheet.setColumnWidth(0, 18000);
        CellStyle title = createReadmeTitleStyle(wb);
        String[] lines = {
            "Tenant Config Package Import Template",
            "Contains 8 sheets: job_definition / file_channel_config / alert_routing_config",
            "  / pipeline_definition / pipeline_step_definition",
            "  / workflow_definition / workflow_node / workflow_edge",
            "Import flow: upload -> preview -> apply (single transaction)",
            "Cross-sheet references validated: pipeline.job_code -> job_definition,",
            "  wf_node.related_job_code -> job_definition,",
            "  wf_node.related_pipeline_code -> pipeline_definition",
            "Job definitions: INSERT if not found, UPDATE mutable fields if found.",
            "All other types: UPSERT by unique key."
        };
        for (int i = 0; i < lines.length; i++) {
            Row row = sheet.createRow(i);
            Cell cell = row.createCell(0);
            cell.setCellValue(lines[i]);
            if (i == 0) cell.setCellStyle(title);
        }
    }

    // ── field guide sheet ─────────────────────────────────────────────────────

    private void createFieldGuideSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("填写说明");

        sheet.setColumnWidth(0, 7000);   // 所属 Sheet
        sheet.setColumnWidth(1, 7000);   // 列名
        sheet.setColumnWidth(2, 3500);   // 必填
        sheet.setColumnWidth(3, 3500);   // 类型
        sheet.setColumnWidth(4, 14000);  // 可选值
        sheet.setColumnWidth(5, 18000);  // 说明
        sheet.setColumnWidth(6, 7000);   // 示例

        CellStyle headStyle = ConsoleExcelStyles.createHeaderStyle(wb);
        CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
        bodyStyle.setWrapText(true);
        CellStyle requiredStyle = createRequiredMarkStyle(wb);
        requiredStyle.setWrapText(true);
        CellStyle optionalStyle = createOptionalMarkStyle(wb);
        optionalStyle.setWrapText(true);

        // header row
        Row header = sheet.createRow(0);
        header.setHeightInPoints(22);
        String[] headers = {"所属 Sheet", "列名", "必填", "类型", "可选值", "说明", "示例"};
        for (int i = 0; i < headers.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headStyle);
        }

        record SheetSpec(String sheetName, List<String> columns,
                Map<String, ConsoleExcelStyles.ColumnGuide> guides) {}

        List<SheetSpec> specs = List.of(
                new SheetSpec(JOB_SHEET, JOB_COLUMNS, buildJobGuides()),
                new SheetSpec(CHANNEL_SHEET, CHANNEL_COLUMNS, buildChannelGuides()),
                new SheetSpec(ROUTING_SHEET, ROUTING_COLUMNS, buildRoutingGuides()),
                new SheetSpec(PIPELINE_SHEET, PIPELINE_COLUMNS, buildPipelineGuides()),
                new SheetSpec(STEP_SHEET, STEP_COLUMNS, buildStepGuides()),
                new SheetSpec(WF_DEF_SHEET, WF_DEF_COLUMNS, buildWfDefGuides()),
                new SheetSpec(WF_NODE_SHEET, WF_NODE_COLUMNS, buildWfNodeGuides()),
                new SheetSpec(WF_EDGE_SHEET, WF_EDGE_COLUMNS, buildWfEdgeGuides()));

        int rowIdx = 1;
        for (SheetSpec spec : specs) {
            for (int ci = 0; ci < spec.columns().size(); ci++) {
                String colName = spec.columns().get(ci);
                ConsoleExcelStyles.ColumnGuide guide = spec.guides().get(colName);
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(18);

                writeGuideCell(row, 0, ci == 0 ? spec.sheetName() : "", bodyStyle);
                writeGuideCell(row, 1, colName, bodyStyle);
                boolean isRequired = guide != null && guide.required();
                writeGuideCell(row, 2, isRequired ? "★ 必填" : "选填", isRequired ? requiredStyle : optionalStyle);
                writeGuideCell(row, 3, guide != null ? guide.formatHint() : "", bodyStyle);
                String values = guide != null && !guide.allowedValues().isEmpty()
                        ? String.join(" / ", guide.allowedValues()) : "";
                writeGuideCell(row, 4, values, bodyStyle);
                writeGuideCell(row, 5, guide != null ? guide.description() : "", bodyStyle);
                writeGuideCell(row, 6, guide != null ? guide.example() : "", bodyStyle);
            }
        }
    }

    private void writeGuideCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    // ── validation appliers (dropdown / boolean constraints) ──────────────────

    private void applyJobValidations(Sheet sheet) {
        addDropdownValidation(sheet, 3, JOB_TYPES.toArray(String[]::new), "job_type", "请选择作业类型");
        addDropdownValidation(sheet, 7, SCHEDULE_TYPES.toArray(String[]::new), "schedule_type", "请选择调度类型");
        addDropdownValidation(sheet, 11, RETRY_POLICIES.toArray(String[]::new), "retry_policy", "请选择重试策略");
        addDropdownValidation(sheet, 14, SHARD_STRATEGIES.toArray(String[]::new), "shard_strategy", "请选择分片策略");
        addBooleanValidation(sheet, new int[]{18}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyChannelValidations(Sheet sheet) {
        addDropdownValidation(sheet, 3, CHANNEL_TYPES.toArray(String[]::new), "channel_type", "请选择通道类型");
        addDropdownValidation(sheet, 5, AUTH_TYPES.toArray(String[]::new), "auth_type", "请选择认证类型");
        addDropdownValidation(sheet, 7, RECEIPT_POLICIES.toArray(String[]::new), "receipt_policy", "请选择回执策略");
        addBooleanValidation(sheet, new int[]{9}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyRoutingValidations(Sheet sheet) {
        addDropdownValidation(sheet, 5, SEVERITIES.toArray(String[]::new), "severity", "请选择告警级别");
        addBooleanValidation(sheet, new int[]{11}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyPipelineValidations(Sheet sheet) {
        addDropdownValidation(sheet, 3, PIPELINE_TYPES.toArray(String[]::new), "pipeline_type", "请选择流水线类型");
        addBooleanValidation(sheet, new int[]{7}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyStepValidations(Sheet sheet) {
        addDropdownValidation(sheet, 4, STAGE_CODES.toArray(String[]::new), "stage_code", "请选择阶段");
        addDropdownValidation(sheet, 9, RETRY_POLICIES.toArray(String[]::new), "retry_policy", "请选择重试策略");
        addBooleanValidation(sheet, new int[]{11}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyWfDefValidations(Sheet sheet) {
        addDropdownValidation(sheet, 3, WORKFLOW_TYPES.toArray(String[]::new), "workflow_type", "请选择工作流类型");
        addBooleanValidation(sheet, new int[]{5}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyWfNodeValidations(Sheet sheet) {
        addDropdownValidation(sheet, 5, NODE_TYPES.toArray(String[]::new), "node_type", "请选择节点类型");
        addDropdownValidation(sheet, 11, RETRY_POLICIES.toArray(String[]::new), "retry_policy", "请选择重试策略");
        addBooleanValidation(sheet, new int[]{15}, "enabled", "请填写 TRUE 或 FALSE");
    }

    private void applyWfEdgeValidations(Sheet sheet) {
        addDropdownValidation(sheet, 5, EDGE_TYPES.toArray(String[]::new), "edge_type", "请选择边类型");
        addBooleanValidation(sheet, new int[]{7}, "enabled", "请填写 TRUE 或 FALSE");
    }

    // ── column guides ─────────────────────────────────────────────────────────

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildJobGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户，留空使用当前租户。", "字符串", "tenant-a")),
                Map.entry("job_code", requiredColumn("作业唯一编码。", "字符串", "JOB_IMPORT_CUSTOMER")),
                Map.entry("job_name", requiredColumn("作业名称。", "字符串", "客户导入作业")),
                Map.entry("job_type", requiredColumn("作业类型。", "枚举", "IMPORT", "GENERAL", "IMPORT", "EXPORT", "DISPATCH", "WORKFLOW")),
                Map.entry("biz_type", optionalColumn("业务类型标识。", "字符串", "CUSTOMER")),
                Map.entry("queue_code", optionalColumn("资源队列编码。", "字符串", "import-queue")),
                Map.entry("worker_group", optionalColumn("Worker 分组。", "字符串", "import")),
                Map.entry("schedule_type", requiredColumn("调度类型。", "枚举", "MANUAL", "CRON", "FIXED_RATE", "MANUAL", "EVENT", "ONE_TIME")),
                Map.entry("schedule_expr", optionalColumn("调度表达式，CRON 时填写。", "字符串", "0 2 * * *")),
                Map.entry("calendar_code", optionalColumn("业务日历编码。", "字符串", "default-calendar")),
                Map.entry("window_code", optionalColumn("批量窗口编码。", "字符串", "always-open")),
                Map.entry("retry_policy", optionalColumn("重试策略。", "枚举", "NONE", "NONE", "FIXED", "EXPONENTIAL")),
                Map.entry("retry_max_count", optionalColumn("最大重试次数。", "整数", "3")),
                Map.entry("timeout_seconds", optionalColumn("超时秒数。", "整数", "3600")),
                Map.entry("shard_strategy", optionalColumn("分片策略。", "枚举", "NONE", "NONE", "STATIC", "DYNAMIC", "AUTO")),
                Map.entry("execution_handler", optionalColumn("执行处理器 Bean 名称（新建时设置，更新时忽略）。", "字符串", "importJobHandler")),
                Map.entry("param_schema", optionalColumn("参数 JSON Schema（新建时设置，更新时忽略）。", "JSON", "{}")),
                Map.entry("default_params", optionalColumn("默认参数 JSON（新建时设置，更新时忽略）。", "JSON", "{}")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
                Map.entry("description", optionalColumn("描述。", "字符串", "客户文件导入作业")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildChannelGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户。", "字符串", "tenant-a")),
                Map.entry("channel_code", requiredColumn("通道唯一编码。", "字符串", "sftp_inbound")),
                Map.entry("channel_name", requiredColumn("通道名称。", "字符串", "SFTP 入站通道")),
                Map.entry("channel_type", requiredColumn("通道类型。", "枚举", "SFTP", "SFTP", "API", "EMAIL", "NAS", "OSS", "LOCAL", "API_PUSH")),
                Map.entry("target_endpoint", optionalColumn("目标地址。", "字符串", "sftp.example.com")),
                Map.entry("auth_type", requiredColumn("认证类型。", "枚举", "PASSWORD", "NONE", "PASSWORD", "KEY_PAIR", "TOKEN", "OAUTH2", "CUSTOM")),
                Map.entry("config_json", requiredColumn("通道配置 JSON。", "JSON", "{}")),
                Map.entry("receipt_policy", requiredColumn("回执策略。", "枚举", "NONE", "NONE", "SYNC", "ASYNC", "POLLING")),
                Map.entry("timeout_seconds", optionalColumn("超时秒数。", "整数", "30")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildRoutingGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户。", "字符串", "tenant-a")),
                Map.entry("route_code", requiredColumn("路由唯一编码。", "字符串", "RT_BATCH_ERROR")),
                Map.entry("route_name", requiredColumn("路由名称。", "字符串", "批处理异常路由")),
                Map.entry("team", requiredColumn("负责团队。", "字符串", "ops")),
                Map.entry("alert_group", requiredColumn("告警分组。", "字符串", "batch")),
                Map.entry("severity", requiredColumn("告警级别。", "枚举", "ERROR", "INFO", "WARN", "ERROR", "CRITICAL")),
                Map.entry("receiver", requiredColumn("接收方。", "字符串", "slack-ops")),
                Map.entry("group_by", optionalColumn("聚合分组键。", "字符串", "job_code")),
                Map.entry("group_wait_seconds", optionalColumn("聚合等待秒数。", "整数", "30")),
                Map.entry("group_interval_seconds", optionalColumn("聚合间隔秒数。", "整数", "300")),
                Map.entry("repeat_interval_seconds", optionalColumn("重复通知间隔秒数。", "整数", "3600")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
                Map.entry("description", optionalColumn("描述。", "字符串", "批处理失败默认路由")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildPipelineGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户。", "字符串", "tenant-a")),
                Map.entry("job_code", requiredColumn("关联作业编码，与 version 组成联合键。", "字符串", "JOB_IMPORT_CUSTOMER")),
                Map.entry("pipeline_name", requiredColumn("流水线名称。", "字符串", "客户导入流水线")),
                Map.entry("pipeline_type", requiredColumn("流水线类型。", "枚举", "IMPORT", "IMPORT", "EXPORT", "DISPATCH")),
                Map.entry("biz_type", optionalColumn("业务类型。", "字符串", "CUSTOMER")),
                Map.entry("worker_group", optionalColumn("Worker 分组。", "字符串", "import")),
                Map.entry("version", requiredColumn("版本号，与 job_code 组成联合键。", "整数", "1")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
                Map.entry("description", optionalColumn("描述。", "字符串", "客户文件导入流水线")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildStepGuides() {
        return Map.ofEntries(
                Map.entry("job_code", requiredColumn("关联流水线的 job_code。", "字符串", "JOB_IMPORT_CUSTOMER")),
                Map.entry("version", requiredColumn("关联流水线的版本号。", "整数", "1")),
                Map.entry("step_code", requiredColumn("步骤唯一编码。", "字符串", "STEP_PARSE")),
                Map.entry("step_name", requiredColumn("步骤名称。", "字符串", "解析文件")),
                Map.entry("stage_code", requiredColumn("阶段。", "枚举", "PARSE", "RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "GENERATE", "TRANSFER", "DISPATCH", "ACK")),
                Map.entry("step_order", optionalColumn("步骤顺序号。", "整数", "1")),
                Map.entry("impl_code", optionalColumn("实现插件编码。", "字符串", "csvParser")),
                Map.entry("step_params", optionalColumn("步骤参数 JSON。", "JSON", "{}")),
                Map.entry("timeout_seconds", optionalColumn("超时秒数。", "整数", "300")),
                Map.entry("retry_policy", optionalColumn("重试策略。", "枚举", "NONE", "NONE", "FIXED", "EXPONENTIAL")),
                Map.entry("retry_max_count", optionalColumn("最大重试次数。", "整数", "0")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfDefGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户。", "字符串", "tenant-a")),
                Map.entry("workflow_code", requiredColumn("工作流唯一编码，三个工作流 sheet 共用此键。", "字符串", "WF_SETTLEMENT")),
                Map.entry("workflow_name", requiredColumn("工作流名称。", "字符串", "清算工作流")),
                Map.entry("workflow_type", requiredColumn("工作流拓扑类型。", "枚举", "DAG", "DAG", "PIPELINE", "MIXED")),
                Map.entry("version", requiredColumn("版本号。", "整数", "1")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")),
                Map.entry("description", optionalColumn("描述。", "字符串", "清算批量工作流")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfNodeGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户。", "字符串", "tenant-a")),
                Map.entry("workflow_code", requiredColumn("所属工作流编码。", "字符串", "WF_SETTLEMENT")),
                Map.entry("workflow_version", requiredColumn("所属工作流版本号。", "整数", "1")),
                Map.entry("node_code", requiredColumn("节点唯一编码。", "字符串", "NODE_IMPORT")),
                Map.entry("node_name", requiredColumn("节点名称。", "字符串", "导入节点")),
                Map.entry("node_type", requiredColumn("节点类型。", "枚举", "JOB", "TASK", "GATEWAY", "FILE_STEP", "START", "END", "JOB")),
                Map.entry("related_job_code", optionalColumn("关联的作业编码，需在本包 job_definition sheet 或库中存在。", "字符串", "JOB_IMPORT_CUSTOMER")),
                Map.entry("related_pipeline_code", optionalColumn("关联的流水线 job_code，需在本包 pipeline_definition sheet 或库中存在。", "字符串", "JOB_IMPORT_CUSTOMER")),
                Map.entry("worker_group", optionalColumn("Worker 分组。", "字符串", "import")),
                Map.entry("window_code", optionalColumn("批量窗口编码。", "字符串", "always-open")),
                Map.entry("node_order", optionalColumn("节点顺序号。", "整数", "1")),
                Map.entry("retry_policy", optionalColumn("重试策略。", "枚举", "NONE", "NONE", "FIXED", "EXPONENTIAL")),
                Map.entry("retry_max_count", optionalColumn("最大重试次数。", "整数", "0")),
                Map.entry("timeout_seconds", optionalColumn("超时秒数。", "整数", "3600")),
                Map.entry("node_params", optionalColumn("节点参数 JSON。", "JSON", "{}")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")));
    }

    private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfEdgeGuides() {
        return Map.ofEntries(
                Map.entry("tenant_id", optionalColumn("所属租户。", "字符串", "tenant-a")),
                Map.entry("workflow_code", requiredColumn("所属工作流编码。", "字符串", "WF_SETTLEMENT")),
                Map.entry("workflow_version", requiredColumn("所属工作流版本号。", "整数", "1")),
                Map.entry("from_node_code", requiredColumn("源节点编码。", "字符串", "NODE_IMPORT")),
                Map.entry("to_node_code", requiredColumn("目标节点编码。", "字符串", "NODE_EXPORT")),
                Map.entry("edge_type", requiredColumn("边类型。", "枚举", "SUCCESS", "SUCCESS", "FAILURE", "CONDITION", "ALWAYS")),
                Map.entry("condition_expr", optionalColumn("CONDITION 类型的条件表达式。", "字符串", "")),
                Map.entry("enabled", optionalColumn("是否启用。", "布尔值", "TRUE", "TRUE", "FALSE")));
    }

    // ── response builders ─────────────────────────────────────────────────────

    private TenantConfigPackageExcelPreviewResponse toPreviewResponse(
            String uploadToken, String fileName, PackageValidationResult result) {
        List<SheetStats> sheets = List.of(
                toSheetStats(result.jobs()),
                toSheetStats(result.channels()),
                toSheetStats(result.routings()),
                toSheetStats(result.pipelines()),
                toSheetStats(result.steps()),
                toSheetStats(result.wfDefs()),
                toSheetStats(result.wfNodes()),
                toSheetStats(result.wfEdges()));
        List<IssueDto> issues = result.allIssues().stream()
                .map(i -> new IssueDto(i.sheetName(), i.rowNo(), i.columnName(), i.message()))
                .toList();
        int total = sheets.stream().mapToInt(SheetStats::totalRows).sum();
        int valid = sheets.stream().mapToInt(SheetStats::validRows).sum();
        return new TenantConfigPackageExcelPreviewResponse(
                uploadToken, fileName, total, valid, total - valid, sheets, issues);
    }

    private SheetStats toSheetStats(SheetResult r) {
        return new SheetStats(r.sheetName(), r.total(), r.valid(), r.invalid());
    }

    // ── session ───────────────────────────────────────────────────────────────

    private PackageExcelSession loadSession(String uploadToken) {
        PackageExcelSession session = Guard.requireFound(
                importStore.get(uploadToken), "excel upload session not found");
        tenantGuard.assertTenantAllowed(session.tenantId());
        return session;
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private static String normalize(String value) {
        return ConsoleTextSanitizer.normalize(value);
    }

    private static String normalizeEnum(String value) {
        String n = normalize(value);
        return n == null ? null : n.toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private static Integer parseInteger(String value) {
        String n = normalize(value);
        if (!StringUtils.hasText(n)) return null;
        try { return Integer.parseInt(n); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean parseBoolean(String value, Boolean defaultValue) {
        String n = normalize(value);
        if (!StringUtils.hasText(n)) return defaultValue;
        String upper = n.toUpperCase(Locale.ROOT);
        if (Set.of("TRUE", "Y", "1", "YES").contains(upper)) return true;
        if (Set.of("FALSE", "N", "0", "NO").contains(upper)) return false;
        return defaultValue;
    }

    private static String safeOp(String operatorId) {
        return ConsoleTextSanitizer.safeInput(operatorId, 64);
    }

    private static void addIssues(List<String> rowIssues, String sheetName, int rowNo, List<WorkbookIssue> issues) {
        for (String msg : rowIssues) {
            issues.add(new WorkbookIssue(sheetName, rowNo, null, msg));
        }
    }

    private static Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter fmt) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
            String header = normalize(fmt.formatCellValue(headerRow.getCell(c)));
            if (StringUtils.hasText(header)) index.put(header, c);
        }
        return index;
    }

    private static void validateSheetHeaders(
            String sheetName, Map<String, Integer> headerIndex, Set<String> required) {
        List<String> missing = required.stream()
                .filter(h -> !headerIndex.containsKey(h)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(
                    ResultCode.INVALID_ARGUMENT,
                    "sheet [" + sheetName + "] missing required headers: " + missing);
        }
    }

    private static boolean isRowBlank(Row row, DataFormatter fmt) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (StringUtils.hasText(fmt.formatCellValue(row.getCell(c)))) return false;
        }
        return true;
    }

    private static String cellText(Row row, Integer colIdx, DataFormatter fmt) {
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx);
        return cell == null ? null : fmt.formatCellValue(cell);
    }

    private static String fileNameOrDefault(String originalFileName) {
        return StringUtils.hasText(originalFileName)
                ? originalFileName : "tenant-config-package.xlsx";
    }

    private static ResponseEntity<InputStreamResource> excelResponse(String fileName, byte[] bytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(XLSX_MEDIA_TYPE)
                .body(new InputStreamResource(new ByteArrayInputStream(bytes)));
    }

    // ── internal records ──────────────────────────────────────────────────────

    private record PackageExportData(
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> channels,
            List<Map<String, Object>> routings,
            List<Map<String, Object>> pipelines,
            List<Map<String, Object>> steps,
            List<Map<String, Object>> wfDefs,
            List<Map<String, Object>> wfNodes,
            List<Map<String, Object>> wfEdges) {}

    private record ApplyContext(String tenantId, String operatorId, String reason, String traceId) {}

    private record ApplyStats(int inserted, int updated) {}

    private record SheetResult(
            String sheetName,
            int total,
            List<Map<String, String>> validRows,
            List<WorkbookIssue> issues) {
        int valid() { return validRows.size(); }
        int invalid() { return total - validRows.size(); }
    }

    private record PackageValidationResult(
            SheetResult jobs,
            SheetResult channels,
            SheetResult routings,
            SheetResult pipelines,
            SheetResult steps,
            SheetResult wfDefs,
            SheetResult wfNodes,
            SheetResult wfEdges,
            List<WorkbookIssue> crossRefIssues) {

        int totalInvalid() {
            return jobs.invalid() + channels.invalid() + routings.invalid()
                    + pipelines.invalid() + steps.invalid()
                    + wfDefs.invalid() + wfNodes.invalid() + wfEdges.invalid()
                    + crossRefIssues.size();
        }

        List<Map<String, String>> validJobs() { return jobs.validRows(); }
        List<Map<String, String>> validChannels() { return channels.validRows(); }
        List<Map<String, String>> validRoutings() { return routings.validRows(); }
        List<Map<String, String>> validPipelines() { return pipelines.validRows(); }
        List<Map<String, String>> validSteps() { return steps.validRows(); }
        List<Map<String, String>> validWfDefs() { return wfDefs.validRows(); }
        List<Map<String, String>> validWfNodes() { return wfNodes.validRows(); }
        List<Map<String, String>> validWfEdges() { return wfEdges.validRows(); }

        List<WorkbookIssue> allIssues() {
            List<WorkbookIssue> all = new ArrayList<>();
            all.addAll(jobs.issues()); all.addAll(channels.issues());
            all.addAll(routings.issues()); all.addAll(pipelines.issues());
            all.addAll(steps.issues()); all.addAll(wfDefs.issues());
            all.addAll(wfNodes.issues()); all.addAll(wfEdges.issues());
            all.addAll(crossRefIssues);
            return all;
        }
    }
}
