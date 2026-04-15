package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.ConfigPackageExcelValidator.*;
import static com.example.batch.console.infrastructure.ConfigPackageExcelWorkbookWriter.*;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsoleTenantConfigPackageExcelApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.infrastructure.ConfigPackageExcelValidator.PackageValidationResult;
import com.example.batch.console.infrastructure.ConfigPackageExcelValidator.SheetResult;
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
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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

  private static final String KEY_ID = "id";
  private static final String EMPTY = "";
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

  private ConfigPackageExcelValidator validator() {
    return new ConfigPackageExcelValidator(jobDefinitionMapper, pipelineDefinitionMapper);
  }

  private final ConfigPackageExcelWorkbookWriter workbookWriter =
      new ConfigPackageExcelWorkbookWriter();

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
        workbookWriter.buildExportWorkbook(
            List.of(jobs, channels, routings, pipelines, steps, wfDefs, wfNodes, wfEdges));
    String fileName = "tenant-config-package-" + tid + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return excelResponse(fileName, bytes);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    byte[] bytes = workbookWriter.buildTemplateWorkbook();
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
    PackageValidationResult result = validator().validate(session);
    return toPreviewResponse(uploadToken, session.fileName(), result);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    PackageExcelSession session = loadSession(uploadToken);
    PackageValidationResult result = validator().validate(session);
    byte[] bytes = workbookWriter.buildPreviewWorkbook(session, result);
    return excelResponse(
        ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()), bytes);
  }

  @Override
  @Transactional
  public TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request) {
    PackageExcelSession session = loadSession(uploadToken);
    PackageValidationResult result = validator().validate(session);
    if (result.totalInvalid() > 0) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    ApplyContext ctx =
        new ApplyContext(
            session.tenantId(), metadata.operatorId(), request.getReason(), metadata.traceId());

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
    if (Set.of("TRUE", "Y", "1", "YES").contains(upper)) return true;
    if (Set.of("FALSE", "N", "0", "NO").contains(upper)) return false;
    return defaultValue;
  }

  private static String safeOp(String operatorId) {
    return ConsoleTextSanitizer.safeInput(operatorId, 64);
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

  private record ApplyContext(String tenantId, String operatorId, String reason, String traceId) {}

  private record ApplyStats(int inserted, int updated) {}
}
