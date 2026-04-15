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

import com.example.batch.common.enums.AlertSeverity;
import com.example.batch.common.enums.FileChannelAuthType;
import com.example.batch.common.enums.FileChannelType;
import com.example.batch.common.enums.FileReceiptPolicy;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
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

/** 租户配置包 Excel 导入服务：8 sheet 单事务写库，含跨 sheet 依赖校验。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleTenantConfigPackageExcelApplicationService
    implements ConsoleTenantConfigPackageExcelApplicationService {

  // ── column name constants ─────────────────────────────────────────────────
  private static final String COL_TENANT_ID = "tenant_id";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_DESCRIPTION = "description";
  private static final String COL_VERSION = "version";
  private static final String COL_BIZ_TYPE = "biz_type";
  private static final String COL_WORKER_GROUP = "worker_group";
  private static final String COL_WINDOW_CODE = "window_code";
  private static final String COL_RETRY_POLICY = "retry_policy";
  private static final String COL_RETRY_MAX_COUNT = "retry_max_count";
  private static final String COL_TIMEOUT_SECONDS = "timeout_seconds";
  private static final String COL_SHARD_STRATEGY = "shard_strategy";
  private static final String COL_JOB_CODE = "job_code";
  private static final String COL_JOB_NAME = "job_name";
  private static final String COL_JOB_TYPE = "job_type";
  private static final String COL_SCHEDULE_TYPE = "schedule_type";
  private static final String COL_SCHEDULE_EXPR = "schedule_expr";
  private static final String COL_CALENDAR_CODE = "calendar_code";
  private static final String COL_QUEUE_CODE = "queue_code";
  private static final String COL_PARAM_SCHEMA = "param_schema";
  private static final String COL_CHANNEL_TYPE = "channel_type";
  private static final String COL_AUTH_TYPE = "auth_type";
  private static final String COL_RECEIPT_POLICY = "receipt_policy";
  private static final String COL_SEVERITY = "severity";
  private static final String COL_PIPELINE_TYPE = "pipeline_type";
  private static final String COL_STAGE_CODE = "stage_code";
  private static final String COL_WORKFLOW_CODE = "workflow_code";
  private static final String COL_WORKFLOW_NAME = "workflow_name";
  private static final String COL_WORKFLOW_TYPE = "workflow_type";
  private static final String COL_WORKFLOW_VERSION = "workflow_version";
  private static final String COL_NODE_CODE = "node_code";
  private static final String COL_NODE_NAME = "node_name";
  private static final String COL_NODE_TYPE = "node_type";
  private static final String COL_RELATED_JOB_CODE = "related_job_code";
  private static final String COL_RELATED_PIPELINE_CODE = "related_pipeline_code";
  private static final String COL_NODE_PARAMS = "node_params";
  private static final String COL_EDGE_TYPE = "edge_type";
  private static final String COL_FROM_NODE_CODE = "from_node_code";
  private static final String COL_TO_NODE_CODE = "to_node_code";

  // ── key / separator constants ─────────────────────────────────────────────
  private static final String KEY_SEP_COLON = ":";
  private static final String KEY_SEP_HASH = "#";
  private static final String KEY_ID = "id";
  private static final String GUIDE_EMPTY_JSON = "{}";
  private static final String GUIDE_VERSION_ONE = "1";
  private static final String EMPTY = "";

  // ── guide label constants ─────────────────────────────────────────────────
  private static final String COL_EXECUTION_HANDLER = "execution_handler";
  private static final String COL_DEFAULT_PARAMS = "default_params";
  private static final String COL_CHANNEL_CODE = "channel_code";
  private static final String COL_CHANNEL_NAME = "channel_name";
  private static final String COL_CONFIG_JSON = "config_json";
  private static final String COL_ROUTE_CODE = "route_code";
  private static final String COL_ROUTE_NAME = "route_name";
  private static final String COL_TEAM = "team";
  private static final String COL_ALERT_GROUP = "alert_group";
  private static final String COL_RECEIVER = "receiver";
  private static final String COL_PIPELINE_NAME = "pipeline_name";
  private static final String COL_STEP_CODE = "step_code";
  private static final String COL_STEP_NAME = "step_name";
  private static final String COL_NODE_ORDER = "node_order";
  private static final String COL_CONDITION_EXPR = "condition_expr";
  private static final String GUIDE_IMPORT = "IMPORT";
  private static final String GUIDE_TIMEOUT_DESC = "超时秒数。";
  private static final String GUIDE_DESC_DESC = "描述。";
  private static final String GUIDE_STR = "字符串";
  private static final String GUIDE_ENUM = "枚举";
  private static final String GUIDE_INT = "整数";
  private static final String GUIDE_BOOL = "布尔值";
  private static final String GUIDE_JSON = "JSON";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_NONE = "NONE";
  private static final String GUIDE_BOOL_HINT = "请填写 TRUE 或 FALSE";
  private static final String GUIDE_ENABLED_DESC = "是否启用。";
  private static final String GUIDE_TENANT_DESC = "所属租户。";
  private static final String GUIDE_TENANT_EXAMPLE = "tenant-a";
  private static final String GUIDE_JOB_EXAMPLE = "JOB_IMPORT_CUSTOMER";
  private static final String GUIDE_DISPATCH = "DISPATCH";

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
  private static final List<String> JOB_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_JOB_CODE,
          COL_JOB_NAME,
          COL_JOB_TYPE,
          COL_BIZ_TYPE,
          COL_QUEUE_CODE,
          COL_WORKER_GROUP,
          COL_SCHEDULE_TYPE,
          COL_SCHEDULE_EXPR,
          COL_CALENDAR_CODE,
          COL_WINDOW_CODE,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_TIMEOUT_SECONDS,
          COL_SHARD_STRATEGY,
          COL_EXECUTION_HANDLER,
          COL_PARAM_SCHEMA,
          COL_DEFAULT_PARAMS,
          COL_ENABLED,
          COL_DESCRIPTION);

  private static final List<String> CHANNEL_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_CHANNEL_CODE,
          COL_CHANNEL_NAME,
          COL_CHANNEL_TYPE,
          "target_endpoint",
          COL_AUTH_TYPE,
          COL_CONFIG_JSON,
          COL_RECEIPT_POLICY,
          COL_TIMEOUT_SECONDS,
          COL_ENABLED);

  private static final List<String> ROUTING_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_ROUTE_CODE,
          COL_ROUTE_NAME,
          COL_TEAM,
          COL_ALERT_GROUP,
          COL_SEVERITY,
          COL_RECEIVER,
          "group_by",
          "group_wait_seconds",
          "group_interval_seconds",
          "repeat_interval_seconds",
          COL_ENABLED,
          COL_DESCRIPTION);

  private static final List<String> PIPELINE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_JOB_CODE,
          COL_PIPELINE_NAME,
          COL_PIPELINE_TYPE,
          COL_BIZ_TYPE,
          COL_WORKER_GROUP,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  private static final List<String> STEP_COLUMNS =
      List.of(
          COL_JOB_CODE,
          COL_VERSION,
          COL_STEP_CODE,
          COL_STEP_NAME,
          COL_STAGE_CODE,
          "step_order",
          "impl_code",
          "step_params",
          COL_TIMEOUT_SECONDS,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_ENABLED);

  private static final List<String> WF_DEF_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_NAME,
          COL_WORKFLOW_TYPE,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  private static final List<String> WF_NODE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          COL_NODE_CODE,
          COL_NODE_NAME,
          COL_NODE_TYPE,
          COL_RELATED_JOB_CODE,
          COL_RELATED_PIPELINE_CODE,
          COL_WORKER_GROUP,
          COL_WINDOW_CODE,
          COL_NODE_ORDER,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_TIMEOUT_SECONDS,
          COL_NODE_PARAMS,
          COL_ENABLED);

  private static final List<String> WF_EDGE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          COL_FROM_NODE_CODE,
          COL_TO_NODE_CODE,
          COL_EDGE_TYPE,
          COL_CONDITION_EXPR,
          COL_ENABLED);

  // ── enum sets ─────────────────────────────────────────────────────────────
  private static final Set<String> JOB_TYPES = JobType.codes();
  private static final Set<String> SCHEDULE_TYPES = Set.of("CRON", "FIXED_RATE", "MANUAL");
  private static final Set<String> RETRY_POLICIES = RetryPolicyType.codes();
  private static final Set<String> SHARD_STRATEGIES = ShardStrategy.codes();
  private static final Set<String> CHANNEL_TYPES = FileChannelType.codes();
  private static final Set<String> AUTH_TYPES = FileChannelAuthType.codes();
  private static final Set<String> RECEIPT_POLICIES = FileReceiptPolicy.codes();
  private static final Set<String> SEVERITIES = AlertSeverity.codes();
  private static final Set<String> PIPELINE_TYPES = PipelineType.codes();
  private static final Set<String> STAGE_CODES =
      Set.of(
          "RECEIVE",
          "PREPROCESS",
          "PARSE",
          "VALIDATE",
          "LOAD",
          "GENERATE",
          "TRANSFER",
          GUIDE_DISPATCH,
          "ACK");
  private static final Set<String> WORKFLOW_TYPES = WorkflowType.codes();
  private static final Set<String> NODE_TYPES = WorkflowNodeType.codes();
  private static final Set<String> EDGE_TYPES = WorkflowEdgeType.codes();

  private static final MediaType XLSX_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

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
    List<Map<String, Object>> jobs =
        toJobRows(jobDefinitionMapper.selectByQuery(JobDefinitionQuery.ofTenant(tid, null)));
    List<Map<String, Object>> channels =
        fileChannelConfigMapper.selectByQuery(tid, null, null, null, null);
    List<Map<String, Object>> routings =
        alertRoutingConfigMapper.selectByQuery(tid, null, null, null, null, null);
    List<Map<String, Object>> pipelines =
        pipelineDefinitionMapper.selectByQuery(tid, null, null, null, null);
    List<Map<String, Object>> steps = collectPipelineSteps(pipelines);
    List<WorkflowDefinitionEntity> wfEntities =
        workflowDefinitionMapper.selectByQuery(WorkflowDefinitionQuery.ofTenant(tid, null));
    List<Map<String, Object>> wfDefs = toWfDefRows(wfEntities);
    List<Map<String, Object>> wfNodes = collectWorkflowNodes(tid, wfEntities);
    List<Map<String, Object>> wfEdges = collectWorkflowEdges(tid, wfEntities);
    byte[] bytes =
        buildExportWorkbook(
            new PackageExportData(
                jobs, channels, routings, pipelines, steps, wfDefs, wfNodes, wfEdges));
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
        token,
        fileName,
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
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()), bytes);
  }

  @Override
  @Transactional
  public TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request) {
    PackageExcelSession session = loadSession(uploadToken);
    PackageValidationResult result = validate(session);
    if (result.totalInvalid() > 0) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    ApplyContext ctx =
        new ApplyContext(
            session.tenantId(), metadata.operatorId(),
            request.getReason(), metadata.traceId());

    ApplyStats jobStats = applyJobs(result.validJobs(), ctx);
    ApplyStats channelStats = applyChannels(result.validChannels(), ctx);
    ApplyStats routingStats = applyRoutings(result.validRoutings(), ctx);
    ApplyStats pipelineStats = applyPipelines(result.validPipelines(), result.validSteps(), ctx);
    ApplyStats wfStats =
        applyWorkflows(result.validWfDefs(), result.validWfNodes(), result.validWfEdges(), ctx);

    importStore.remove(uploadToken);
    return new TenantConfigPackageExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        jobStats.inserted(),
        jobStats.updated(),
        channelStats.inserted(),
        channelStats.updated(),
        routingStats.inserted(),
        routingStats.updated(),
        pipelineStats.inserted(),
        pipelineStats.updated(),
        wfStats.inserted(),
        wfStats.updated());
  }

  // ── parsing ───────────────────────────────────────────────────────────────

  private PackageExcelSession parseWorkbook(byte[] bytes, String tenantId, String fileName) {
    try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      return new PackageExcelSession(
          fileName,
          tenantId,
          Instant.now(),
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
          ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + e.getMessage());
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
      if (tenantId != null && !StringUtils.hasText(values.get(COL_TENANT_ID))) {
        values.put(COL_TENANT_ID, tenantId);
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
    SheetResult wfEdges =
        validateWfEdgeRows(
            tid, session.workflowEdgeRows(), wfDefs.validRows(), wfNodes.validRows());
    List<WorkbookIssue> crossIssues =
        validateCrossReferences(
            tid,
            jobs.validRows(),
            pipelines.validRows(),
            wfNodes.validRows(),
            session.pipelineRows());
    return new PackageValidationResult(
        jobs, channels, routings, pipelines, steps, wfDefs, wfNodes, wfEdges, crossIssues);
  }

  private SheetResult validateJobRows(String tenantId, List<Map<String, String>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      if (!hasText(jobCode)) ri.add("job_code is required");
      if (!hasText(normalize(row.get(COL_JOB_NAME)))) ri.add("job_name is required");
      String jobType = normalizeEnum(row.get(COL_JOB_TYPE));
      if (!hasText(jobType)) ri.add("job_type is required");
      else if (!JOB_TYPES.contains(jobType)) ri.add("job_type must be one of " + JOB_TYPES);
      String scheduleType = normalizeEnum(row.get(COL_SCHEDULE_TYPE));
      if (!hasText(scheduleType)) ri.add("schedule_type is required");
      else if (!SCHEDULE_TYPES.contains(scheduleType))
        ri.add("schedule_type must be one of " + SCHEDULE_TYPES);
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      String shardStrategy = normalizeEnum(row.get(COL_SHARD_STRATEGY));
      if (hasText(shardStrategy) && !SHARD_STRATEGIES.contains(shardStrategy))
        ri.add("shard_strategy must be one of " + SHARD_STRATEGIES);
      String paramSchema = row.get(COL_PARAM_SCHEMA);
      if (hasText(paramSchema)) {
        try {
          JsonUtils.fromJson(paramSchema, Object.class);
        } catch (Exception e) {
          ri.add("param_schema must be valid JSON");
        }
      }
      if (hasText(jobCode) && !seen.add(tenantId + KEY_SEP_HASH + jobCode))
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
      String code = normalize(row.get(COL_CHANNEL_CODE));
      if (!hasText(code)) ri.add("channel_code is required");
      if (!hasText(normalize(row.get(COL_CHANNEL_NAME)))) ri.add("channel_name is required");
      String channelType = normalizeEnum(row.get(COL_CHANNEL_TYPE));
      if (!hasText(channelType)) ri.add("channel_type is required");
      else if (!CHANNEL_TYPES.contains(channelType))
        ri.add("channel_type must be one of " + CHANNEL_TYPES);
      String authType = normalizeEnum(row.get(COL_AUTH_TYPE));
      if (!hasText(authType)) ri.add("auth_type is required");
      else if (!AUTH_TYPES.contains(authType)) ri.add("auth_type must be one of " + AUTH_TYPES);
      String receiptPolicy = normalizeEnum(row.get(COL_RECEIPT_POLICY));
      if (!hasText(receiptPolicy)) ri.add("receipt_policy is required");
      else if (!RECEIPT_POLICIES.contains(receiptPolicy))
        ri.add("receipt_policy must be one of " + RECEIPT_POLICIES);
      String configJson = row.get(COL_CONFIG_JSON);
      if (!hasText(configJson)) ri.add("config_json is required");
      else {
        try {
          JsonUtils.fromJson(configJson, Object.class);
        } catch (Exception e) {
          ri.add("config_json must be valid JSON");
        }
      }
      if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code))
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
      String code = normalize(row.get(COL_ROUTE_CODE));
      if (!hasText(code)) ri.add("route_code is required");
      if (!hasText(normalize(row.get(COL_ROUTE_NAME)))) ri.add("route_name is required");
      if (!hasText(normalize(row.get(COL_TEAM)))) ri.add("team is required");
      if (!hasText(normalize(row.get(COL_ALERT_GROUP)))) ri.add("alert_group is required");
      String severity = normalizeEnum(row.get(COL_SEVERITY));
      if (!hasText(severity)) ri.add("severity is required");
      else if (!SEVERITIES.contains(severity)) ri.add("severity must be one of " + SEVERITIES);
      if (!hasText(normalize(row.get(COL_RECEIVER)))) ri.add("receiver is required");
      if (hasText(code) && !seen.add(tenantId + KEY_SEP_HASH + code))
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
      String jobCode = normalize(row.get(COL_JOB_CODE));
      String version = normalize(row.get(COL_VERSION));
      if (!hasText(jobCode)) ri.add("job_code is required");
      if (!hasText(normalize(row.get(COL_PIPELINE_NAME)))) ri.add("pipeline_name is required");
      String pType = normalizeEnum(row.get(COL_PIPELINE_TYPE));
      if (!hasText(pType)) ri.add("pipeline_type is required");
      else if (!PIPELINE_TYPES.contains(pType))
        ri.add("pipeline_type must be one of " + PIPELINE_TYPES);
      if (!hasText(version)) ri.add("version is required");
      else {
        try {
          Integer.parseInt(version);
        } catch (NumberFormatException e) {
          ri.add("version must be integer");
        }
      }
      if (hasText(jobCode)
          && hasText(version)
          && !seen.add(tenantId + KEY_SEP_HASH + jobCode + KEY_SEP_COLON + version))
        ri.add("duplicate pipeline key (job_code + version): " + jobCode + KEY_SEP_COLON + version);
      addIssues(ri, PIPELINE_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
      rowNo++;
    }
    return new SheetResult(PIPELINE_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateStepRows(
      List<Map<String, String>> rows, List<Map<String, String>> validPipelineRows) {
    Set<String> pipelineKeys =
        validPipelineRows.stream()
            .map(
                r -> normalize(r.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String jobCode = normalize(row.get(COL_JOB_CODE));
      String version = normalize(row.get(COL_VERSION));
      String stepCode = normalize(row.get(COL_STEP_CODE));
      if (!hasText(jobCode)) ri.add("job_code is required");
      if (!hasText(version)) ri.add("version is required");
      if (!hasText(stepCode)) ri.add("step_code is required");
      if (!hasText(normalize(row.get(COL_STEP_NAME)))) ri.add("step_name is required");
      String stageCode = normalizeEnum(row.get(COL_STAGE_CODE));
      if (!hasText(stageCode)) ri.add("stage_code is required");
      else if (!STAGE_CODES.contains(stageCode)) ri.add("stage_code must be one of " + STAGE_CODES);
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      String pipelineKey = jobCode + KEY_SEP_COLON + version;
      if (hasText(jobCode) && hasText(version) && !pipelineKeys.contains(pipelineKey))
        ri.add("no matching pipeline for job_code + version: " + pipelineKey);
      if (hasText(jobCode)
          && hasText(version)
          && hasText(stepCode)
          && !seen.add(pipelineKey + KEY_SEP_HASH + stepCode))
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
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      String version = normalize(row.get(COL_VERSION));
      if (!hasText(wfCode)) ri.add("workflow_code is required");
      if (!hasText(normalize(row.get(COL_WORKFLOW_NAME)))) ri.add("workflow_name is required");
      String wfType = normalizeEnum(row.get(COL_WORKFLOW_TYPE));
      if (!hasText(wfType)) ri.add("workflow_type is required");
      else if (!WORKFLOW_TYPES.contains(wfType))
        ri.add("workflow_type must be one of " + WORKFLOW_TYPES);
      if (!hasText(version)) ri.add("version is required");
      else {
        try {
          Integer.parseInt(version);
        } catch (NumberFormatException e) {
          ri.add("version must be integer");
        }
      }
      if (hasText(wfCode)
          && hasText(version)
          && !seen.add(tenantId + KEY_SEP_HASH + wfCode + KEY_SEP_COLON + version))
        ri.add("duplicate workflow definition: " + wfCode + KEY_SEP_COLON + version);
      addIssues(ri, WF_DEF_SHEET, rowNo, issues);
      if (ri.isEmpty()) valid.add(row);
      rowNo++;
    }
    return new SheetResult(WF_DEF_SHEET, rows.size(), valid, issues);
  }

  private SheetResult validateWfNodeRows(
      String tenantId, List<Map<String, String>> rows, List<Map<String, String>> validWfDefs) {
    Set<String> wfKeys =
        validWfDefs.stream()
            .map(
                r ->
                    normalize(r.get(COL_WORKFLOW_CODE))
                        + KEY_SEP_COLON
                        + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
      String nodeCode = normalize(row.get(COL_NODE_CODE));
      if (!hasText(wfCode)) ri.add("workflow_code is required");
      if (!hasText(wfVersion)) ri.add("workflow_version is required");
      if (!hasText(nodeCode)) ri.add("node_code is required");
      if (!hasText(normalize(row.get(COL_NODE_NAME)))) ri.add("node_name is required");
      String nodeType = normalizeEnum(row.get(COL_NODE_TYPE));
      if (!hasText(nodeType)) ri.add("node_type is required");
      else if (!NODE_TYPES.contains(nodeType)) ri.add("node_type must be one of " + NODE_TYPES);
      String retryPolicy = normalizeEnum(row.get(COL_RETRY_POLICY));
      if (hasText(retryPolicy) && !RETRY_POLICIES.contains(retryPolicy))
        ri.add("retry_policy must be one of " + RETRY_POLICIES);
      String nodeParams = row.get(COL_NODE_PARAMS);
      if (hasText(nodeParams)) {
        try {
          JsonUtils.fromJson(nodeParams, Object.class);
        } catch (Exception e) {
          ri.add("node_params must be valid JSON");
        }
      }
      String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
      if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey))
        ri.add("workflow node references missing definition: " + wfKey);
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(nodeCode)
          && !seen.add(wfKey + KEY_SEP_HASH + nodeCode))
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
    Set<String> wfKeys =
        validWfDefs.stream()
            .map(
                r ->
                    normalize(r.get(COL_WORKFLOW_CODE))
                        + KEY_SEP_COLON
                        + normalize(r.get(COL_VERSION)))
            .collect(Collectors.toSet());
    Set<String> nodeKeys =
        validNodes.stream()
            .map(
                r ->
                    normalize(r.get(COL_WORKFLOW_CODE))
                        + KEY_SEP_COLON
                        + normalize(r.get(COL_WORKFLOW_VERSION))
                        + KEY_SEP_HASH
                        + normalize(r.get(COL_NODE_CODE)))
            .collect(Collectors.toSet());
    List<WorkbookIssue> issues = new ArrayList<>();
    List<Map<String, String>> valid = new ArrayList<>();
    int rowNo = 2;
    for (Map<String, String> row : rows) {
      List<String> ri = new ArrayList<>();
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      String wfVersion = normalize(row.get(COL_WORKFLOW_VERSION));
      String fromNode = normalize(row.get(COL_FROM_NODE_CODE));
      String toNode = normalize(row.get(COL_TO_NODE_CODE));
      if (!hasText(wfCode)) ri.add("workflow_code is required");
      if (!hasText(wfVersion)) ri.add("workflow_version is required");
      if (!hasText(fromNode)) ri.add("from_node_code is required");
      if (!hasText(toNode)) ri.add("to_node_code is required");
      String edgeType = normalizeEnum(row.get(COL_EDGE_TYPE));
      if (!hasText(edgeType)) ri.add("edge_type is required");
      else if (!EDGE_TYPES.contains(edgeType)) ri.add("edge_type must be one of " + EDGE_TYPES);
      String wfKey = wfCode + KEY_SEP_COLON + wfVersion;
      if (hasText(wfCode) && hasText(wfVersion) && !wfKeys.contains(wfKey))
        ri.add("workflow edge references missing definition: " + wfKey);
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(fromNode)
          && !nodeKeys.contains(wfKey + KEY_SEP_HASH + fromNode))
        ri.add("from_node_code references unknown node: " + fromNode);
      if (hasText(wfCode)
          && hasText(wfVersion)
          && hasText(toNode)
          && !nodeKeys.contains(wfKey + KEY_SEP_HASH + toNode))
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
    Set<String> jobCodesInExcel =
        validJobs.stream()
            .map(r -> normalize(r.get(COL_JOB_CODE)))
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
    Set<String> pipelineJobCodesInExcel =
        validPipelines.stream()
            .map(r -> normalize(r.get(COL_JOB_CODE)))
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
    List<WorkbookIssue> issues = new ArrayList<>();

    // pipeline.job_code → job_definition
    int rowNo = 2;
    for (Map<String, String> row : allPipelineRows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      if (hasText(jobCode)
          && !jobCodesInExcel.contains(jobCode)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode) == null) {
        issues.add(
            new WorkbookIssue(
                PIPELINE_SHEET,
                rowNo,
                COL_JOB_CODE,
                "job_code references unknown job definition: " + jobCode));
      }
      rowNo++;
    }

    // wf_node.related_job_code / related_pipeline_code
    rowNo = 2;
    for (Map<String, String> row : validWfNodes) {
      String relatedJob = normalize(row.get(COL_RELATED_JOB_CODE));
      if (hasText(relatedJob)
          && !jobCodesInExcel.contains(relatedJob)
          && jobDefinitionMapper.selectByUniqueKey(tenantId, relatedJob) == null) {
        issues.add(
            new WorkbookIssue(
                WF_NODE_SHEET,
                rowNo,
                COL_RELATED_JOB_CODE,
                "related_job_code references unknown job definition: " + relatedJob));
      }
      String relatedPipeline = normalize(row.get(COL_RELATED_PIPELINE_CODE));
      if (hasText(relatedPipeline) && !pipelineJobCodesInExcel.contains(relatedPipeline)) {
        List<Map<String, Object>> found =
            pipelineDefinitionMapper.selectByQuery(
                tenantId, relatedPipeline, null, null, new PageRequest(1, 1));
        if (found == null || found.isEmpty()) {
          issues.add(
              new WorkbookIssue(
                  WF_NODE_SHEET,
                  rowNo,
                  COL_RELATED_PIPELINE_CODE,
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
      String jobCode = normalize(row.get(COL_JOB_CODE));
      JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode);
      if (existing == null) {
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setTenantId(ctx.tenantId());
        entity.setJobCode(jobCode);
        entity.setJobName(normalize(row.get(COL_JOB_NAME)));
        entity.setJobType(normalizeEnum(row.get(COL_JOB_TYPE)));
        entity.setBizType(normalize(row.get(COL_BIZ_TYPE)));
        entity.setQueueCode(normalize(row.get(COL_QUEUE_CODE)));
        entity.setWorkerGroup(normalize(row.get(COL_WORKER_GROUP)));
        entity.setScheduleType(normalizeEnum(row.get(COL_SCHEDULE_TYPE)));
        entity.setScheduleExpr(normalize(row.get(COL_SCHEDULE_EXPR)));
        entity.setCalendarCode(normalize(row.get(COL_CALENDAR_CODE)));
        entity.setWindowCode(normalize(row.get(COL_WINDOW_CODE)));
        entity.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
        entity.setRetryMaxCount(parseInteger(row.get(COL_RETRY_MAX_COUNT)));
        entity.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
        entity.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
        entity.setExecutionHandler(normalize(row.get(COL_EXECUTION_HANDLER)));
        entity.setParamSchema(normalize(row.get(COL_PARAM_SCHEMA)));
        entity.setDefaultParams(normalize(row.get(COL_DEFAULT_PARAMS)));
        entity.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
        entity.setDescription(normalize(row.get(COL_DESCRIPTION)));
        entity.setCreatedBy(safeOp(ctx.operatorId()));
        entity.setUpdatedBy(safeOp(ctx.operatorId()));
        jobDefinitionMapper.insert(entity);
        inserted++;
      } else {
        JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
        param.setTenantId(ctx.tenantId());
        param.setJobCode(jobCode);
        param.setJobName(normalize(row.get(COL_JOB_NAME)));
        param.setQueueCode(normalize(row.get(COL_QUEUE_CODE)));
        param.setWorkerGroup(normalize(row.get(COL_WORKER_GROUP)));
        param.setScheduleExpr(normalize(row.get(COL_SCHEDULE_EXPR)));
        param.setCalendarCode(normalize(row.get(COL_CALENDAR_CODE)));
        param.setWindowCode(normalize(row.get(COL_WINDOW_CODE)));
        param.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
        param.setRetryMaxCount(parseInteger(row.get(COL_RETRY_MAX_COUNT)));
        param.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
        param.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
        param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
        param.setDescription(normalize(row.get(COL_DESCRIPTION)));
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
      String code = normalize(row.get(COL_CHANNEL_CODE));
      Map<String, Object> existing =
          fileChannelConfigMapper.selectByUniqueKey(ctx.tenantId(), code);
      FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
      param.setTenantId(ctx.tenantId());
      param.setChannelCode(code);
      param.setChannelName(normalize(row.get(COL_CHANNEL_NAME)));
      param.setChannelType(normalizeEnum(row.get(COL_CHANNEL_TYPE)));
      param.setTargetEndpoint(normalize(row.get("target_endpoint")));
      param.setAuthType(normalizeEnum(row.get(COL_AUTH_TYPE)));
      param.setConfigJson(row.get(COL_CONFIG_JSON));
      param.setReceiptPolicy(normalizeEnum(row.get(COL_RECEIPT_POLICY)));
      param.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
      param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      param.setCreatedBy(safeOp(ctx.operatorId()));
      param.setUpdatedBy(safeOp(ctx.operatorId()));
      fileChannelConfigMapper.upsertFileChannelConfig(param);
      if (existing == null || existing.isEmpty()) inserted++;
      else updated++;
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyRoutings(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0, updated = 0;
    for (Map<String, String> row : rows) {
      String code = normalize(row.get(COL_ROUTE_CODE));
      Map<String, Object> existing =
          alertRoutingConfigMapper.selectByUniqueKey(ctx.tenantId(), code);
      AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
      param.setTenantId(ctx.tenantId());
      param.setRouteCode(code);
      param.setRouteName(normalize(row.get(COL_ROUTE_NAME)));
      param.setTeam(normalize(row.get(COL_TEAM)));
      param.setAlertGroup(normalize(row.get(COL_ALERT_GROUP)));
      param.setSeverity(normalizeEnum(row.get(COL_SEVERITY)));
      param.setReceiver(normalize(row.get(COL_RECEIVER)));
      param.setGroupBy(normalize(row.get("group_by")));
      param.setGroupWaitSeconds(parseInteger(row.get("group_wait_seconds")));
      param.setGroupIntervalSeconds(parseInteger(row.get("group_interval_seconds")));
      param.setRepeatIntervalSeconds(parseInteger(row.get("repeat_interval_seconds")));
      param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      param.setDescription(normalize(row.get(COL_DESCRIPTION)));
      param.setCreatedBy(safeOp(ctx.operatorId()));
      param.setUpdatedBy(safeOp(ctx.operatorId()));
      alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
      if (existing == null || existing.isEmpty()) inserted++;
      else updated++;
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyPipelines(
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> stepRows,
      ApplyContext ctx) {
    Map<String, List<Map<String, String>>> stepsByKey =
        stepRows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        normalize(r.get(COL_JOB_CODE))
                            + KEY_SEP_COLON
                            + normalize(r.get(COL_VERSION))));
    int inserted = 0, updated = 0;
    for (Map<String, String> row : pipelineRows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      int version = Integer.parseInt(normalize(row.get(COL_VERSION)));
      Map<String, Object> existing =
          pipelineDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode, version);
      Long pipelineId;
      if (existing == null || existing.isEmpty()) {
        Map<String, Object> params = buildPipelineInsertParams(row, ctx);
        pipelineDefinitionMapper.insert(params);
        pipelineId = ((Number) params.get(KEY_ID)).longValue();
        inserted++;
      } else {
        pipelineId = ((Number) existing.get(KEY_ID)).longValue();
        pipelineDefinitionMapper.update(buildPipelineUpdateParams(pipelineId, row, ctx));
        updated++;
      }
      pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(pipelineId);
      for (Map<String, String> step :
          stepsByKey.getOrDefault(jobCode + KEY_SEP_COLON + version, List.of())) {
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
    Map<String, List<Map<String, String>>> nodesByWf =
        nodeRows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        normalize(r.get(COL_WORKFLOW_CODE))
                            + KEY_SEP_COLON
                            + normalize(r.get(COL_WORKFLOW_VERSION))));
    Map<String, List<Map<String, String>>> edgesByWf =
        edgeRows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        normalize(r.get(COL_WORKFLOW_CODE))
                            + KEY_SEP_COLON
                            + normalize(r.get(COL_WORKFLOW_VERSION))));
    int inserted = 0, updated = 0;
    for (Map<String, String> row : defRows) {
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      int version = Integer.parseInt(normalize(row.get(COL_VERSION)));
      WorkflowDefinitionEntity existing =
          workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
      WorkflowDefinitionUpsertParam defParam = new WorkflowDefinitionUpsertParam();
      defParam.setTenantId(ctx.tenantId());
      defParam.setWorkflowCode(wfCode);
      defParam.setWorkflowName(normalize(row.get(COL_WORKFLOW_NAME)));
      defParam.setWorkflowType(normalizeEnum(row.get(COL_WORKFLOW_TYPE)));
      defParam.setVersion(version);
      defParam.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      defParam.setDescription(normalize(row.get(COL_DESCRIPTION)));
      defParam.setCreatedBy(safeOp(ctx.operatorId()));
      defParam.setUpdatedBy(safeOp(ctx.operatorId()));
      workflowDefinitionMapper.upsertWorkflowDefinition(defParam);
      if (existing == null) inserted++;
      else updated++;

      WorkflowDefinitionEntity saved =
          workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
      if (saved == null || saved.getId() == null) continue;
      Long defId = saved.getId();
      String wfKey = wfCode + KEY_SEP_COLON + version;
      applyWfNodes(defId, nodesByWf.getOrDefault(wfKey, List.of()));
      applyWfEdges(defId, edgesByWf.getOrDefault(wfKey, List.of()));
    }
    return new ApplyStats(inserted, updated);
  }

  private void applyWfNodes(Long defId, List<Map<String, String>> nodes) {
    for (Map<String, String> node : nodes) {
      WorkflowNodeUpsertParam p = new WorkflowNodeUpsertParam();
      p.setWorkflowDefinitionId(defId);
      p.setNodeCode(normalize(node.get(COL_NODE_CODE)));
      p.setNodeName(normalize(node.get(COL_NODE_NAME)));
      p.setNodeType(normalizeEnum(node.get(COL_NODE_TYPE)));
      p.setRelatedJobCode(normalize(node.get(COL_RELATED_JOB_CODE)));
      p.setRelatedPipelineCode(normalize(node.get(COL_RELATED_PIPELINE_CODE)));
      p.setWorkerGroup(normalize(node.get(COL_WORKER_GROUP)));
      p.setWindowCode(normalize(node.get(COL_WINDOW_CODE)));
      p.setNodeOrder(parseInteger(node.get(COL_NODE_ORDER)));
      p.setRetryPolicy(normalizeEnum(node.get(COL_RETRY_POLICY)));
      p.setRetryMaxCount(parseInteger(node.get(COL_RETRY_MAX_COUNT)));
      p.setTimeoutSeconds(parseInteger(node.get(COL_TIMEOUT_SECONDS)));
      p.setNodeParams(normalize(node.get(COL_NODE_PARAMS)));
      p.setEnabled(parseBoolean(node.get(COL_ENABLED), true));
      workflowNodeMapper.upsertWorkflowNode(p);
    }
  }

  private void applyWfEdges(Long defId, List<Map<String, String>> edges) {
    for (Map<String, String> edge : edges) {
      WorkflowEdgeUpsertParam p = new WorkflowEdgeUpsertParam();
      p.setWorkflowDefinitionId(defId);
      p.setFromNodeCode(normalize(edge.get(COL_FROM_NODE_CODE)));
      p.setToNodeCode(normalize(edge.get(COL_TO_NODE_CODE)));
      p.setEdgeType(normalizeEnum(edge.get(COL_EDGE_TYPE)));
      p.setConditionExpr(normalize(edge.get(COL_CONDITION_EXPR)));
      p.setEnabled(parseBoolean(edge.get(COL_ENABLED), true));
      workflowEdgeMapper.upsertWorkflowEdge(p);
    }
  }

  // ── pipeline param builders ───────────────────────────────────────────────

  private Map<String, Object> buildPipelineInsertParams(Map<String, String> row, ApplyContext ctx) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("tenantId", ctx.tenantId());
    p.put("jobCode", normalize(row.get(COL_JOB_CODE)));
    p.put("pipelineName", normalize(row.get(COL_PIPELINE_NAME)));
    p.put("pipelineType", normalizeEnum(row.get(COL_PIPELINE_TYPE)));
    p.put("bizType", normalize(row.get(COL_BIZ_TYPE)));
    p.put("workerGroup", normalize(row.get(COL_WORKER_GROUP)));
    p.put(COL_VERSION, parseInteger(row.get(COL_VERSION)));
    p.put(COL_ENABLED, parseBoolean(row.get(COL_ENABLED), true));
    p.put(COL_DESCRIPTION, normalize(row.get(COL_DESCRIPTION)));
    p.put("createdBy", safeOp(ctx.operatorId()));
    p.put("updatedBy", safeOp(ctx.operatorId()));
    p.put(KEY_ID, null);
    return p;
  }

  private Map<String, Object> buildPipelineUpdateParams(
      Long id, Map<String, String> row, ApplyContext ctx) {
    Map<String, Object> p = buildPipelineInsertParams(row, ctx);
    p.put(KEY_ID, id);
    return p;
  }

  private Map<String, Object> buildStepInsertParams(Long pipelineId, Map<String, String> step) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("pipelineDefinitionId", pipelineId);
    p.put("stepCode", normalize(step.get(COL_STEP_CODE)));
    p.put("stepName", normalize(step.get(COL_STEP_NAME)));
    p.put("stageCode", normalizeEnum(step.get(COL_STAGE_CODE)));
    p.put("stepOrder", parseInteger(step.get("step_order")));
    p.put("implCode", normalize(step.get("impl_code")));
    p.put("stepParams", normalize(step.get("step_params")));
    p.put("timeoutSeconds", parseInteger(step.get(COL_TIMEOUT_SECONDS)));
    p.put("retryPolicy", normalizeEnum(step.get(COL_RETRY_POLICY)));
    p.put("retryMaxCount", parseInteger(step.get(COL_RETRY_MAX_COUNT)));
    p.put(COL_ENABLED, parseBoolean(step.get(COL_ENABLED), true));
    return p;
  }

  // ── export helpers ────────────────────────────────────────────────────────

  private List<Map<String, Object>> toJobRows(List<JobDefinitionEntity> entities) {
    return entities.stream()
        .map(
            e -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put(COL_TENANT_ID, e.getTenantId());
              m.put(COL_JOB_CODE, e.getJobCode());
              m.put(COL_JOB_NAME, e.getJobName());
              m.put(COL_JOB_TYPE, e.getJobType());
              m.put(COL_BIZ_TYPE, e.getBizType());
              m.put(COL_QUEUE_CODE, e.getQueueCode());
              m.put(COL_WORKER_GROUP, e.getWorkerGroup());
              m.put(COL_SCHEDULE_TYPE, e.getScheduleType());
              m.put(COL_SCHEDULE_EXPR, e.getScheduleExpr());
              m.put(COL_CALENDAR_CODE, e.getCalendarCode());
              m.put(COL_WINDOW_CODE, e.getWindowCode());
              m.put(COL_RETRY_POLICY, e.getRetryPolicy());
              m.put(COL_RETRY_MAX_COUNT, e.getRetryMaxCount());
              m.put(COL_TIMEOUT_SECONDS, e.getTimeoutSeconds());
              m.put(COL_SHARD_STRATEGY, e.getShardStrategy());
              m.put(COL_EXECUTION_HANDLER, e.getExecutionHandler());
              m.put(COL_PARAM_SCHEMA, e.getParamSchema());
              m.put(COL_DEFAULT_PARAMS, e.getDefaultParams());
              m.put(COL_ENABLED, e.getEnabled());
              m.put(COL_DESCRIPTION, e.getDescription());
              return m;
            })
        .collect(Collectors.toList());
  }

  private List<Map<String, Object>> collectPipelineSteps(List<Map<String, Object>> pipelines) {
    List<Map<String, Object>> allSteps = new ArrayList<>();
    for (Map<String, Object> pipeline : pipelines) {
      Long pipelineId = ((Number) pipeline.get(KEY_ID)).longValue();
      String jobCode = String.valueOf(pipeline.get(COL_JOB_CODE));
      String version = String.valueOf(pipeline.get(COL_VERSION));
      for (Map<String, Object> step :
          pipelineStepDefinitionMapper.selectByPipelineDefinitionId(pipelineId)) {
        Map<String, Object> enriched = new LinkedHashMap<>(step);
        enriched.put(COL_JOB_CODE, jobCode);
        enriched.put(COL_VERSION, version);
        allSteps.add(enriched);
      }
    }
    return allSteps;
  }

  private List<Map<String, Object>> toWfDefRows(List<WorkflowDefinitionEntity> entities) {
    return entities.stream()
        .map(
            e -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put(COL_TENANT_ID, e.getTenantId());
              m.put(COL_WORKFLOW_CODE, e.getWorkflowCode());
              m.put(COL_WORKFLOW_NAME, e.getWorkflowName());
              m.put(COL_WORKFLOW_TYPE, e.getWorkflowType());
              m.put(COL_VERSION, e.getVersion());
              m.put(COL_ENABLED, e.getEnabled());
              m.put(COL_DESCRIPTION, e.getDescription());
              return m;
            })
        .collect(Collectors.toList());
  }

  private List<Map<String, Object>> collectWorkflowNodes(
      String tenantId, List<WorkflowDefinitionEntity> defs) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (WorkflowDefinitionEntity def : defs) {
      List<WorkflowNodeEntity> nodes =
          workflowNodeMapper.selectByQuery(
              new WorkflowNodeQuery(
                  tenantId, def.getId(), def.getWorkflowCode(), null, null, null, null));
      for (WorkflowNodeEntity node : nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(COL_TENANT_ID, tenantId);
        m.put(COL_WORKFLOW_CODE, def.getWorkflowCode());
        m.put(COL_WORKFLOW_VERSION, def.getVersion());
        m.put(COL_NODE_CODE, node.getNodeCode());
        m.put(COL_NODE_NAME, node.getNodeName());
        m.put(COL_NODE_TYPE, node.getNodeType());
        m.put(COL_RELATED_JOB_CODE, node.getRelatedJobCode());
        m.put(COL_RELATED_PIPELINE_CODE, node.getRelatedPipelineCode());
        m.put(COL_WORKER_GROUP, node.getWorkerGroup());
        m.put(COL_WINDOW_CODE, node.getWindowCode());
        m.put(COL_NODE_ORDER, node.getNodeOrder());
        m.put(COL_RETRY_POLICY, node.getRetryPolicy());
        m.put(COL_RETRY_MAX_COUNT, node.getRetryMaxCount());
        m.put(COL_TIMEOUT_SECONDS, node.getTimeoutSeconds());
        m.put(COL_NODE_PARAMS, node.getNodeParams());
        m.put(COL_ENABLED, node.getEnabled());
        result.add(m);
      }
    }
    return result;
  }

  private List<Map<String, Object>> collectWorkflowEdges(
      String tenantId, List<WorkflowDefinitionEntity> defs) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (WorkflowDefinitionEntity def : defs) {
      List<WorkflowEdgeEntity> edges =
          workflowEdgeMapper.selectByQuery(
              new WorkflowEdgeQuery(
                  tenantId, def.getId(), def.getWorkflowCode(), null, null, null, null, null));
      for (WorkflowEdgeEntity edge : edges) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(COL_TENANT_ID, tenantId);
        m.put(COL_WORKFLOW_CODE, def.getWorkflowCode());
        m.put(COL_WORKFLOW_VERSION, def.getVersion());
        m.put(COL_FROM_NODE_CODE, edge.getFromNodeCode());
        m.put(COL_TO_NODE_CODE, edge.getToNodeCode());
        m.put(COL_EDGE_TYPE, edge.getEdgeType());
        m.put(COL_CONDITION_EXPR, edge.getConditionExpr());
        m.put(COL_ENABLED, edge.getEnabled());
        result.add(m);
      }
    }
    return result;
  }

  private byte[] buildExportWorkbook(PackageExportData d) {
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeDataSheet(
          wb, JOB_SHEET, JOB_COLUMNS, buildJobGuides(), d.jobs(), this::applyJobValidations);
      writeDataSheet(
          wb,
          CHANNEL_SHEET,
          CHANNEL_COLUMNS,
          buildChannelGuides(),
          d.channels(),
          this::applyChannelValidations);
      writeDataSheet(
          wb,
          ROUTING_SHEET,
          ROUTING_COLUMNS,
          buildRoutingGuides(),
          d.routings(),
          this::applyRoutingValidations);
      writeDataSheet(
          wb,
          PIPELINE_SHEET,
          PIPELINE_COLUMNS,
          buildPipelineGuides(),
          d.pipelines(),
          this::applyPipelineValidations);
      writeDataSheet(
          wb, STEP_SHEET, STEP_COLUMNS, buildStepGuides(), d.steps(), this::applyStepValidations);
      writeDataSheet(
          wb,
          WF_DEF_SHEET,
          WF_DEF_COLUMNS,
          buildWfDefGuides(),
          d.wfDefs(),
          this::applyWfDefValidations);
      writeDataSheet(
          wb,
          WF_NODE_SHEET,
          WF_NODE_COLUMNS,
          buildWfNodeGuides(),
          d.wfNodes(),
          this::applyWfNodeValidations);
      writeDataSheet(
          wb,
          WF_EDGE_SHEET,
          WF_EDGE_COLUMNS,
          buildWfEdgeGuides(),
          d.wfEdges(),
          this::applyWfEdgeValidations);
      createReadmeSheet(wb);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate export workbook");
    }
  }

  // ── workbook generation ───────────────────────────────────────────────────

  private byte[] buildTemplateWorkbook() {
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeDataSheet(
          wb, JOB_SHEET, JOB_COLUMNS, buildJobGuides(), List.of(), this::applyJobValidations);
      writeDataSheet(
          wb,
          CHANNEL_SHEET,
          CHANNEL_COLUMNS,
          buildChannelGuides(),
          List.of(),
          this::applyChannelValidations);
      writeDataSheet(
          wb,
          ROUTING_SHEET,
          ROUTING_COLUMNS,
          buildRoutingGuides(),
          List.of(),
          this::applyRoutingValidations);
      writeDataSheet(
          wb,
          PIPELINE_SHEET,
          PIPELINE_COLUMNS,
          buildPipelineGuides(),
          List.of(),
          this::applyPipelineValidations);
      writeDataSheet(
          wb, STEP_SHEET, STEP_COLUMNS, buildStepGuides(), List.of(), this::applyStepValidations);
      writeDataSheet(
          wb,
          WF_DEF_SHEET,
          WF_DEF_COLUMNS,
          buildWfDefGuides(),
          List.of(),
          this::applyWfDefValidations);
      writeDataSheet(
          wb,
          WF_NODE_SHEET,
          WF_NODE_COLUMNS,
          buildWfNodeGuides(),
          List.of(),
          this::applyWfNodeValidations);
      writeDataSheet(
          wb,
          WF_EDGE_SHEET,
          WF_EDGE_COLUMNS,
          buildWfEdgeGuides(),
          List.of(),
          this::applyWfEdgeValidations);
      createReadmeSheet(wb);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate template workbook");
    }
  }

  private byte[] buildPreviewWorkbook(PackageExcelSession session, PackageValidationResult result) {
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      writePreviewSheet(
          wb,
          JOB_SHEET,
          JOB_COLUMNS,
          session.jobRows(),
          result.jobs().issues(),
          this::applyJobValidations);
      writePreviewSheet(
          wb,
          CHANNEL_SHEET,
          CHANNEL_COLUMNS,
          session.fileChannelRows(),
          result.channels().issues(),
          this::applyChannelValidations);
      writePreviewSheet(
          wb,
          ROUTING_SHEET,
          ROUTING_COLUMNS,
          session.alertRoutingRows(),
          result.routings().issues(),
          this::applyRoutingValidations);
      writePreviewSheet(
          wb,
          PIPELINE_SHEET,
          PIPELINE_COLUMNS,
          session.pipelineRows(),
          result.pipelines().issues(),
          this::applyPipelineValidations);
      writePreviewSheet(
          wb,
          STEP_SHEET,
          STEP_COLUMNS,
          session.pipelineStepRows(),
          result.steps().issues(),
          this::applyStepValidations);
      writePreviewSheet(
          wb,
          WF_DEF_SHEET,
          WF_DEF_COLUMNS,
          session.workflowDefinitionRows(),
          result.wfDefs().issues(),
          this::applyWfDefValidations);
      writePreviewSheet(
          wb,
          WF_NODE_SHEET,
          WF_NODE_COLUMNS,
          session.workflowNodeRows(),
          result.wfNodes().issues(),
          this::applyWfNodeValidations);
      writePreviewSheet(
          wb,
          WF_EDGE_SHEET,
          WF_EDGE_COLUMNS,
          session.workflowEdgeRows(),
          result.wfEdges().issues(),
          this::applyWfEdgeValidations);
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
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, columns, guides, wb);
    int idx = 1;
    for (Map<String, Object> row : dataRows) {
      Row dataRow = sheet.createRow(idx++);
      for (int c = 0; c < columns.size(); c++) {
        Object val = row.get(columns.get(c));
        dataRow.createCell(c).setCellValue(val == null ? EMPTY : String.valueOf(val));
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
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    writeHeaders(sheet, columns, headerStyle);
    int idx = 1;
    for (Map<String, String> row : dataRows) {
      Row dataRow = sheet.createRow(idx++);
      for (int c = 0; c < columns.size(); c++) {
        String val = row.get(columns.get(c));
        dataRow.createCell(c).setCellValue(val == null ? EMPTY : val);
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

    sheet.setColumnWidth(0, 7000); // 所属 Sheet
    sheet.setColumnWidth(1, 7000); // 列名
    sheet.setColumnWidth(2, 3500); // 必填
    sheet.setColumnWidth(3, 3500); // 类型
    sheet.setColumnWidth(4, 14000); // 可选值
    sheet.setColumnWidth(5, 18000); // 说明
    sheet.setColumnWidth(6, 7000); // 示例

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

    record SheetSpec(
        String sheetName,
        List<String> columns,
        Map<String, ConsoleExcelStyles.ColumnGuide> guides) {}

    List<SheetSpec> specs =
        List.of(
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

        writeGuideCell(row, 0, ci == 0 ? spec.sheetName() : EMPTY, bodyStyle);
        writeGuideCell(row, 1, colName, bodyStyle);
        boolean isRequired = guide != null && guide.required();
        writeGuideCell(
            row, 2, isRequired ? "★ 必填" : "选填", isRequired ? requiredStyle : optionalStyle);
        writeGuideCell(row, 3, guide != null ? guide.formatHint() : EMPTY, bodyStyle);
        String values =
            guide != null && !guide.allowedValues().isEmpty()
                ? String.join(" / ", guide.allowedValues())
                : EMPTY;
        writeGuideCell(row, 4, values, bodyStyle);
        writeGuideCell(row, 5, guide != null ? guide.description() : EMPTY, bodyStyle);
        writeGuideCell(row, 6, guide != null ? guide.example() : EMPTY, bodyStyle);
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
    addDropdownValidation(sheet, 3, JOB_TYPES.toArray(String[]::new), COL_JOB_TYPE, "请选择作业类型");
    addDropdownValidation(
        sheet, 7, SCHEDULE_TYPES.toArray(String[]::new), COL_SCHEDULE_TYPE, "请选择调度类型");
    addDropdownValidation(
        sheet, 11, RETRY_POLICIES.toArray(String[]::new), COL_RETRY_POLICY, "请选择重试策略");
    addDropdownValidation(
        sheet, 14, SHARD_STRATEGIES.toArray(String[]::new), COL_SHARD_STRATEGY, "请选择分片策略");
    addBooleanValidation(sheet, new int[] {18}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyChannelValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, CHANNEL_TYPES.toArray(String[]::new), COL_CHANNEL_TYPE, "请选择通道类型");
    addDropdownValidation(sheet, 5, AUTH_TYPES.toArray(String[]::new), COL_AUTH_TYPE, "请选择认证类型");
    addDropdownValidation(
        sheet, 7, RECEIPT_POLICIES.toArray(String[]::new), COL_RECEIPT_POLICY, "请选择回执策略");
    addBooleanValidation(sheet, new int[] {9}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyRoutingValidations(Sheet sheet) {
    addDropdownValidation(sheet, 5, SEVERITIES.toArray(String[]::new), COL_SEVERITY, "请选择告警级别");
    addBooleanValidation(sheet, new int[] {11}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyPipelineValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, PIPELINE_TYPES.toArray(String[]::new), COL_PIPELINE_TYPE, "请选择流水线类型");
    addBooleanValidation(sheet, new int[] {7}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyStepValidations(Sheet sheet) {
    addDropdownValidation(sheet, 4, STAGE_CODES.toArray(String[]::new), COL_STAGE_CODE, "请选择阶段");
    addDropdownValidation(
        sheet, 9, RETRY_POLICIES.toArray(String[]::new), COL_RETRY_POLICY, "请选择重试策略");
    addBooleanValidation(sheet, new int[] {11}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyWfDefValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, WORKFLOW_TYPES.toArray(String[]::new), COL_WORKFLOW_TYPE, "请选择工作流类型");
    addBooleanValidation(sheet, new int[] {5}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyWfNodeValidations(Sheet sheet) {
    addDropdownValidation(sheet, 5, NODE_TYPES.toArray(String[]::new), COL_NODE_TYPE, "请选择节点类型");
    addDropdownValidation(
        sheet, 11, RETRY_POLICIES.toArray(String[]::new), COL_RETRY_POLICY, "请选择重试策略");
    addBooleanValidation(sheet, new int[] {15}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyWfEdgeValidations(Sheet sheet) {
    addDropdownValidation(sheet, 5, EDGE_TYPES.toArray(String[]::new), COL_EDGE_TYPE, "请选择边类型");
    addBooleanValidation(sheet, new int[] {7}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  // ── column guides ─────────────────────────────────────────────────────────

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildJobGuides() {
    return Map.ofEntries(
        Map.entry(COL_TENANT_ID, optionalColumn("所属租户，留空使用当前租户。", GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_JOB_CODE, requiredColumn("作业唯一编码。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_JOB_NAME, requiredColumn("作业名称。", GUIDE_STR, "客户导入作业")),
        Map.entry(
            COL_JOB_TYPE,
            requiredColumn(
                "作业类型。",
                GUIDE_ENUM,
                GUIDE_IMPORT,
                "GENERAL",
                GUIDE_IMPORT,
                "EXPORT",
                GUIDE_DISPATCH,
                "WORKFLOW")),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型标识。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_QUEUE_CODE, optionalColumn("资源队列编码。", GUIDE_STR, "import-queue")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_SCHEDULE_TYPE,
            requiredColumn(
                "调度类型。",
                GUIDE_ENUM,
                "MANUAL",
                "CRON",
                "FIXED_RATE",
                "MANUAL",
                "EVENT",
                "ONE_TIME")),
        Map.entry(COL_SCHEDULE_EXPR, optionalColumn("调度表达式，CRON 时填写。", GUIDE_STR, "0 2 * * *")),
        Map.entry(COL_CALENDAR_CODE, optionalColumn("业务日历编码。", GUIDE_STR, "default-calendar")),
        Map.entry(COL_WINDOW_CODE, optionalColumn("批量窗口编码。", GUIDE_STR, "always-open")),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "3")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "3600")),
        Map.entry(
            COL_SHARD_STRATEGY,
            optionalColumn(
                "分片策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "STATIC", "DYNAMIC", "AUTO")),
        Map.entry(
            COL_EXECUTION_HANDLER,
            optionalColumn("执行处理器 Bean 名称（新建时设置，更新时忽略）。", GUIDE_STR, "importJobHandler")),
        Map.entry(
            COL_PARAM_SCHEMA,
            optionalColumn("参数 JSON Schema（新建时设置，更新时忽略）。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_DEFAULT_PARAMS,
            optionalColumn("默认参数 JSON（新建时设置，更新时忽略）。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户文件导入作业")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildChannelGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_CHANNEL_CODE, requiredColumn("通道唯一编码。", GUIDE_STR, "sftp_inbound")),
        Map.entry(COL_CHANNEL_NAME, requiredColumn("通道名称。", GUIDE_STR, "SFTP 入站通道")),
        Map.entry(
            COL_CHANNEL_TYPE,
            requiredColumn(
                "通道类型。",
                GUIDE_ENUM,
                "SFTP",
                "SFTP",
                "API",
                "EMAIL",
                "NAS",
                "OSS",
                "LOCAL",
                "API_PUSH")),
        Map.entry("target_endpoint", optionalColumn("目标地址。", GUIDE_STR, "sftp.example.com")),
        Map.entry(
            COL_AUTH_TYPE,
            requiredColumn(
                "认证类型。",
                GUIDE_ENUM,
                "PASSWORD",
                GUIDE_NONE,
                "PASSWORD",
                "KEY_PAIR",
                "TOKEN",
                "OAUTH2",
                "CUSTOM")),
        Map.entry(COL_CONFIG_JSON, requiredColumn("通道配置 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_RECEIPT_POLICY,
            requiredColumn(
                "回执策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "SYNC", "ASYNC", "POLLING")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "30")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildRoutingGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_ROUTE_CODE, requiredColumn("路由唯一编码。", GUIDE_STR, "RT_BATCH_ERROR")),
        Map.entry(COL_ROUTE_NAME, requiredColumn("路由名称。", GUIDE_STR, "批处理异常路由")),
        Map.entry(COL_TEAM, requiredColumn("负责团队。", GUIDE_STR, "ops")),
        Map.entry(COL_ALERT_GROUP, requiredColumn("告警分组。", GUIDE_STR, "batch")),
        Map.entry(
            COL_SEVERITY,
            requiredColumn("告警级别。", GUIDE_ENUM, "ERROR", "INFO", "WARN", "ERROR", "CRITICAL")),
        Map.entry(COL_RECEIVER, requiredColumn("接收方。", GUIDE_STR, "slack-ops")),
        Map.entry("group_by", optionalColumn("聚合分组键。", GUIDE_STR, COL_JOB_CODE)),
        Map.entry("group_wait_seconds", optionalColumn("聚合等待秒数。", GUIDE_INT, "30")),
        Map.entry("group_interval_seconds", optionalColumn("聚合间隔秒数。", GUIDE_INT, "300")),
        Map.entry("repeat_interval_seconds", optionalColumn("重复通知间隔秒数。", GUIDE_INT, "3600")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "批处理失败默认路由")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildPipelineGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            COL_JOB_CODE, requiredColumn("关联作业编码，与 version 组成联合键。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_PIPELINE_NAME, requiredColumn("流水线名称。", GUIDE_STR, "客户导入流水线")),
        Map.entry(
            COL_PIPELINE_TYPE,
            requiredColumn(
                "流水线类型。", GUIDE_ENUM, GUIDE_IMPORT, GUIDE_IMPORT, "EXPORT", GUIDE_DISPATCH)),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_VERSION, requiredColumn("版本号，与 job_code 组成联合键。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户文件导入流水线")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildStepGuides() {
    return Map.ofEntries(
        Map.entry(COL_JOB_CODE, requiredColumn("关联流水线的 job_code。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_VERSION, requiredColumn("关联流水线的版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_STEP_CODE, requiredColumn("步骤唯一编码。", GUIDE_STR, "STEP_PARSE")),
        Map.entry(COL_STEP_NAME, requiredColumn("步骤名称。", GUIDE_STR, "解析文件")),
        Map.entry(
            COL_STAGE_CODE,
            requiredColumn(
                "阶段。",
                GUIDE_ENUM,
                "PARSE",
                "RECEIVE",
                "PREPROCESS",
                "PARSE",
                "VALIDATE",
                "LOAD",
                "GENERATE",
                "TRANSFER",
                GUIDE_DISPATCH,
                "ACK")),
        Map.entry("step_order", optionalColumn("步骤顺序号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry("impl_code", optionalColumn("实现插件编码。", GUIDE_STR, "csvParser")),
        Map.entry("step_params", optionalColumn("步骤参数 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "300")),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "0")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfDefGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            COL_WORKFLOW_CODE,
            requiredColumn("工作流唯一编码，三个工作流 sheet 共用此键。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_NAME, requiredColumn("工作流名称。", GUIDE_STR, "清算工作流")),
        Map.entry(
            COL_WORKFLOW_TYPE,
            requiredColumn("工作流拓扑类型。", GUIDE_ENUM, "DAG", "DAG", "PIPELINE", "MIXED")),
        Map.entry(COL_VERSION, requiredColumn("版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "清算批量工作流")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfNodeGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_WORKFLOW_CODE, requiredColumn("所属工作流编码。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_VERSION, requiredColumn("所属工作流版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_NODE_CODE, requiredColumn("节点唯一编码。", GUIDE_STR, "NODE_IMPORT")),
        Map.entry(COL_NODE_NAME, requiredColumn("节点名称。", GUIDE_STR, "导入节点")),
        Map.entry(
            COL_NODE_TYPE,
            requiredColumn(
                "节点类型。", GUIDE_ENUM, "JOB", "TASK", "GATEWAY", "FILE_STEP", "START", "END", "JOB")),
        Map.entry(
            COL_RELATED_JOB_CODE,
            optionalColumn(
                "关联的作业编码，需在本包 job_definition sheet 或库中存在。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(
            COL_RELATED_PIPELINE_CODE,
            optionalColumn(
                "关联的流水线 job_code，需在本包 pipeline_definition sheet 或库中存在。",
                GUIDE_STR,
                GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(COL_WINDOW_CODE, optionalColumn("批量窗口编码。", GUIDE_STR, "always-open")),
        Map.entry(COL_NODE_ORDER, optionalColumn("节点顺序号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "0")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "3600")),
        Map.entry(COL_NODE_PARAMS, optionalColumn("节点参数 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfEdgeGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_WORKFLOW_CODE, requiredColumn("所属工作流编码。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_VERSION, requiredColumn("所属工作流版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_FROM_NODE_CODE, requiredColumn("源节点编码。", GUIDE_STR, "NODE_IMPORT")),
        Map.entry(COL_TO_NODE_CODE, requiredColumn("目标节点编码。", GUIDE_STR, "NODE_EXPORT")),
        Map.entry(
            COL_EDGE_TYPE,
            requiredColumn(
                "边类型。", GUIDE_ENUM, "SUCCESS", "SUCCESS", "FAILURE", "CONDITION", "ALWAYS")),
        Map.entry(COL_CONDITION_EXPR, optionalColumn("CONDITION 类型的条件表达式。", GUIDE_STR, EMPTY)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  // ── response builders ─────────────────────────────────────────────────────

  private TenantConfigPackageExcelPreviewResponse toPreviewResponse(
      String uploadToken, String fileName, PackageValidationResult result) {
    List<SheetStats> sheets =
        List.of(
            toSheetStats(result.jobs()),
            toSheetStats(result.channels()),
            toSheetStats(result.routings()),
            toSheetStats(result.pipelines()),
            toSheetStats(result.steps()),
            toSheetStats(result.wfDefs()),
            toSheetStats(result.wfNodes()),
            toSheetStats(result.wfEdges()));
    List<IssueDto> issues =
        result.allIssues().stream()
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
    PackageExcelSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
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
    try {
      return Integer.parseInt(n);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Boolean parseBoolean(String value, Boolean defaultValue) {
    String n = normalize(value);
    if (!StringUtils.hasText(n)) return defaultValue;
    String upper = n.toUpperCase(Locale.ROOT);
    if (Set.of(GUIDE_TRUE, "Y", GUIDE_VERSION_ONE, "YES").contains(upper)) return true;
    if (Set.of(GUIDE_FALSE, "N", "0", "NO").contains(upper)) return false;
    return defaultValue;
  }

  private static String safeOp(String operatorId) {
    return ConsoleTextSanitizer.safeInput(operatorId, 64);
  }

  private static void addIssues(
      List<String> rowIssues, String sheetName, int rowNo, List<WorkbookIssue> issues) {
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
    List<String> missing = required.stream().filter(h -> !headerIndex.containsKey(h)).toList();
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
    return StringUtils.hasText(originalFileName) ? originalFileName : "tenant-config-package.xlsx";
  }

  private static ResponseEntity<InputStreamResource> excelResponse(String fileName, byte[] bytes) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
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
    int valid() {
      return validRows.size();
    }

    int invalid() {
      return total - validRows.size();
    }
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
      return jobs.invalid()
          + channels.invalid()
          + routings.invalid()
          + pipelines.invalid()
          + steps.invalid()
          + wfDefs.invalid()
          + wfNodes.invalid()
          + wfEdges.invalid()
          + crossRefIssues.size();
    }

    List<Map<String, String>> validJobs() {
      return jobs.validRows();
    }

    List<Map<String, String>> validChannels() {
      return channels.validRows();
    }

    List<Map<String, String>> validRoutings() {
      return routings.validRows();
    }

    List<Map<String, String>> validPipelines() {
      return pipelines.validRows();
    }

    List<Map<String, String>> validSteps() {
      return steps.validRows();
    }

    List<Map<String, String>> validWfDefs() {
      return wfDefs.validRows();
    }

    List<Map<String, String>> validWfNodes() {
      return wfNodes.validRows();
    }

    List<Map<String, String>> validWfEdges() {
      return wfEdges.validRows();
    }

    List<WorkbookIssue> allIssues() {
      List<WorkbookIssue> all = new ArrayList<>();
      all.addAll(jobs.issues());
      all.addAll(channels.issues());
      all.addAll(routings.issues());
      all.addAll(pipelines.issues());
      all.addAll(steps.issues());
      all.addAll(wfDefs.issues());
      all.addAll(wfNodes.issues());
      all.addAll(wfEdges.issues());
      all.addAll(crossRefIssues);
      return all;
    }
  }
}
