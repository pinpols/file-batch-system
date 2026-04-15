package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeCell;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleWorkflowExcelApplicationService;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.param.WorkflowDefinitionUpsertParam;
import com.example.batch.console.mapper.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.mapper.param.WorkflowNodeUpsertParam;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.WorkflowExcelImportStore;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowDefinitionPayload;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowEdgeIdentity;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowEdgePayload;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowExcelSession;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowIdentity;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeExecution;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeIdentity;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeRelation;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeRuntime;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.request.WorkflowExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionExcelRowResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeExcelRowResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelRowIssueResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeExcelRowResponse;
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
import java.util.Objects;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
 * {@link com.example.batch.console.application.ConsoleWorkflowExcelApplicationService} 的默认实现（POI
 * 解析/生成与内存会话）。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowExcelApplicationService
    implements ConsoleWorkflowExcelApplicationService {

  private static final String DEF_SHEET = "workflow_definition";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_DESCRIPTION = "description";
  private static final String EDGE_SUCCESS = "SUCCESS";
  private static final String GUIDE_STR = "字符串";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_TENANT_ID = "tenant_id";
  private static final String COL_WORKFLOW_CODE = "workflow_code";
  private static final String COL_NODE_TYPE = "node_type";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_EDGE_TYPE = "edge_type";
  private static final String COL_WORKFLOW_TYPE = "workflow_type";
  private static final String COL_WORKFLOW_VERSION = "workflow_version";
  private static final String COL_RETRY_POLICY = "retry_policy";
  private static final String GUIDE_INT = "整数";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String NODE_SHEET = "workflow_node";
  private static final String EDGE_SHEET = "workflow_edge";

  private static final List<String> DEF_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          "workflow_name",
          COL_WORKFLOW_TYPE,
          "version",
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final List<String> NODE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          "node_code",
          "node_name",
          COL_NODE_TYPE,
          "related_job_code",
          "related_pipeline_code",
          "worker_group",
          "window_code",
          "node_order",
          COL_RETRY_POLICY,
          "retry_max_count",
          "timeout_seconds",
          "node_params",
          COL_ENABLED);
  private static final List<String> EDGE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          "from_node_code",
          "to_node_code",
          COL_EDGE_TYPE,
          "condition_expr",
          COL_ENABLED);

  private static final Set<String> DEF_HEADERS = Set.copyOf(DEF_COLUMNS);
  private static final Set<String> NODE_HEADERS = Set.copyOf(NODE_COLUMNS);
  private static final Set<String> EDGE_HEADERS = Set.copyOf(EDGE_COLUMNS);
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> DEF_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn("工作流唯一编码，三个工作流 sheet 都依赖这个键。", GUIDE_STR, "WF_SETTLEMENT")),
          Map.entry("workflow_name", requiredColumn("控制台展示的工作流名称。", GUIDE_STR, "清算工作流")),
          Map.entry(
              COL_WORKFLOW_TYPE,
              requiredColumn("工作流拓扑类型。", "枚举", "DAG", "DAG", "PIPELINE", "MIXED")),
          Map.entry("version", requiredColumn("工作流版本号，节点和边必须使用同一版本。", GUIDE_INT, "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("工作流定义是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(COL_DESCRIPTION, optionalColumn("面向运维人员的说明信息。", GUIDE_STR, "夜间清算编排流程")));
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> NODE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "工作流编码，必须与 workflow_definition.workflow_code 一致。", GUIDE_STR, "WF_SETTLEMENT")),
          Map.entry(
              COL_WORKFLOW_VERSION,
              requiredColumn("工作流版本，必须与 workflow_definition.version 一致。", GUIDE_INT, "1")),
          Map.entry("node_code", requiredColumn("工作流内唯一节点编码。", GUIDE_STR, "LOAD_SOURCE")),
          Map.entry("node_name", requiredColumn("面向运维人员的节点名称。", GUIDE_STR, "加载源文件")),
          Map.entry(
              COL_NODE_TYPE,
              requiredColumn(
                  "编排器识别的节点类型。",
                  "枚举",
                  "TASK",
                  "TASK",
                  "GATEWAY",
                  "FILE_STEP",
                  "START",
                  "END",
                  "JOB")),
          Map.entry(
              "related_job_code", optionalColumn("当该节点触发作业定义时填写。", GUIDE_STR, "JOB_IMPORT_001")),
          Map.entry(
              "related_pipeline_code",
              optionalColumn("当该节点引用 pipeline 定义时填写。", GUIDE_STR, "PIPE_IMPORT_001")),
          Map.entry("worker_group", optionalColumn("运行时使用的执行器分组。", GUIDE_STR, "worker-general")),
          Map.entry("window_code", optionalColumn("系统中已准备好的批量窗口编码。", GUIDE_STR, "WINDOW_NIGHT")),
          Map.entry("node_order", optionalColumn("同层节点的建议执行顺序。", GUIDE_INT, "10")),
          Map.entry(
              COL_RETRY_POLICY,
              optionalColumn("节点失败后的重试策略。", "枚举", "FIXED", "NONE", "FIXED", "EXPONENTIAL")),
          Map.entry("retry_max_count", optionalColumn("最大重试次数，必须大于等于 0。", GUIDE_INT, "3")),
          Map.entry("timeout_seconds", optionalColumn("超时时间（秒），必须大于等于 0。", GUIDE_INT, "1800")),
          Map.entry(
              "node_params", optionalColumn("节点运行参数，请保持为合法 JSON。", "JSON", "{\"mode\":\"full\"}")),
          Map.entry(
              COL_ENABLED, optionalColumn("节点是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  private static final Map<String, ConsoleExcelStyles.ColumnGuide> EDGE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "工作流编码，必须与 workflow_definition.workflow_code 一致。", GUIDE_STR, "WF_SETTLEMENT")),
          Map.entry(
              COL_WORKFLOW_VERSION,
              requiredColumn("工作流版本，必须与 workflow_definition.version 一致。", GUIDE_INT, "1")),
          Map.entry("from_node_code", requiredColumn("依赖关系中的上游节点编码。", GUIDE_STR, "LOAD_SOURCE")),
          Map.entry("to_node_code", requiredColumn("依赖关系中的下游节点编码。", GUIDE_STR, "VALIDATE_FILE")),
          Map.entry(
              COL_EDGE_TYPE,
              requiredColumn(
                  "两个节点之间的流转类型。",
                  "枚举",
                  EDGE_SUCCESS,
                  EDGE_SUCCESS,
                  "FAILURE",
                  "CONDITION",
                  "ALWAYS")),
          Map.entry(
              "condition_expr",
              optionalColumn("当 edge_type 为 CONDITION 时填写条件表达式。", "表达式", "${fileReady == true}")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("该依赖边是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));

  private static final Set<String> WORKFLOW_TYPES = WorkflowType.codes();
  private static final Set<String> NODE_TYPES = WorkflowNodeType.codes();
  private static final Set<String> RETRY_POLICIES = RetryPolicyType.codes();
  private static final Set<String> EDGE_TYPES = WorkflowEdgeType.codes();

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final WorkflowExcelImportStore importStore;

  @Override
  public ResponseEntity<InputStreamResource> exportWorkflowExcel(
      WorkflowDefinitionQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    List<WorkflowDefinitionEntity> definitions =
        workflowDefinitionMapper.selectByQuery(
            new WorkflowDefinitionQuery(
                tenantId,
                request.getWorkflowCode(),
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled(),
                null));
    byte[] workbookBytes = writeWorkbook(tenantId, definitions);
    InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
    String fileName =
        "workflow-maintenance-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
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
    byte[] workbookBytes = writeWorkbook("template", List.of());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("workflow-maintenance-template.xlsx")
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  public ConsoleWorkflowExcelUploadResponse upload(MultipartFile file) throws IOException {
    Guard.require(file != null && !file.isEmpty(), "file is required");
    String tenantId = tenantGuard.resolveTenant(null);
    ParsedWorkbook workbook = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
    String uploadToken =
        importStore.save(
            workbook.fileName(),
            workbook.tenantId(),
            workbook.definitions(),
            workbook.nodes(),
            workbook.edges());
    return new ConsoleWorkflowExcelUploadResponse(
        uploadToken,
        workbook.fileName(),
        workbook.definitions().size(),
        workbook.nodes().size(),
        workbook.edges().size(),
        workbook.definitions().size() + workbook.nodes().size() + workbook.edges().size());
  }

  @Override
  public ConsoleWorkflowExcelPreviewResponse preview(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validate(session);
    return new ConsoleWorkflowExcelPreviewResponse(
        uploadToken,
        session.fileName(),
        validationResult.definitionRows(),
        validationResult.nodeRows(),
        validationResult.edgeRows(),
        validationResult.totalRows(),
        validationResult.validRows(),
        validationResult.invalidRows(),
        validationResult.definitions().stream().map(this::toResponse).toList(),
        validationResult.nodes().stream().map(this::toResponse).toList(),
        validationResult.edges().stream().map(this::toResponse).toList(),
        validationResult.issues());
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validate(session);
    byte[] workbookBytes = writePreviewWorkbook(session, validationResult);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(
                    ConsoleExcelPreviewWorkbookSupport.previewWorkbookFileName(session.fileName()))
                .build()
                .toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  @Override
  @Transactional
  public ConsoleWorkflowExcelApplyResponse apply(
      String uploadToken, WorkflowExcelApplyRequest request) {
    ParsedSession session = loadSession(uploadToken);
    ValidationResult validationResult = validate(session);
    if (validationResult.invalidRows() > 0) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid workflow rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();

    Map<WorkflowKey, List<WorkflowNodeRow>> nodesByWorkflow = groupNodes(validationResult.nodes());
    Map<WorkflowKey, List<WorkflowEdgeRow>> edgesByWorkflow = groupEdges(validationResult.edges());

    ApplyCounters counters = new ApplyCounters();
    for (WorkflowDefinitionRow row : validationResult.definitions()) {
      WorkflowKey key = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.version());
      WorkflowDefinitionEntity existing = applyWorkflowDefinition(row, operatorId, counters);
      WorkflowDefinitionEntity saved =
          workflowDefinitionMapper.selectByUniqueKey(
              row.tenantId(), row.workflowCode(), row.version());
      if (saved == null || saved.getId() == null) {
        throw new BizException(ResultCode.SYSTEM_ERROR, "failed to resolve workflow definition id");
      }
      List<WorkflowNodeRow> workflowNodes = nodesByWorkflow.getOrDefault(key, List.of());
      List<WorkflowEdgeRow> workflowEdges = edgesByWorkflow.getOrDefault(key, List.of());
      applyNodes(saved.getId(), workflowNodes, counters);
      applyEdges(saved.getId(), workflowEdges, counters);
      logDefinitionChange(
          new DefinitionChangeContext(
              row,
              workflowNodes.size(),
              workflowEdges.size(),
              request.getReason(),
              operatorId,
              traceId,
              existing == null ? "CREATE" : "PUBLISH"));
    }

    importStore.remove(uploadToken);
    return new ConsoleWorkflowExcelApplyResponse(
        uploadToken,
        session.tenantId(),
        validationResult.definitionRows(),
        validationResult.nodeRows(),
        validationResult.edgeRows(),
        counters.insertedDefinitions,
        counters.updatedDefinitions,
        counters.insertedNodes,
        counters.updatedNodes,
        counters.insertedEdges,
        counters.updatedEdges);
  }

  private WorkflowDefinitionEntity applyWorkflowDefinition(
      WorkflowDefinitionRow row, String operatorId, ApplyCounters counters) {
    WorkflowDefinitionEntity existing =
        workflowDefinitionMapper.selectByUniqueKey(
            row.tenantId(), row.workflowCode(), row.version());
    WorkflowDefinitionUpsertParam definitionParam = new WorkflowDefinitionUpsertParam();
    definitionParam.setTenantId(row.tenantId());
    definitionParam.setWorkflowCode(row.workflowCode());
    definitionParam.setWorkflowName(row.workflowName());
    definitionParam.setWorkflowType(row.workflowType());
    definitionParam.setVersion(row.version());
    definitionParam.setEnabled(row.enabled());
    definitionParam.setDescription(row.description());
    definitionParam.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    definitionParam.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    workflowDefinitionMapper.upsertWorkflowDefinition(definitionParam);
    if (existing == null) {
      counters.insertedDefinitions++;
    } else {
      counters.updatedDefinitions++;
    }
    return existing;
  }

  private void applyNodes(
      Long definitionId, List<WorkflowNodeRow> workflowNodes, ApplyCounters counters) {
    for (WorkflowNodeRow node : workflowNodes) {
      WorkflowNodeEntity existingNode =
          workflowNodeMapper.selectByUniqueKey(definitionId, node.nodeCode());
      WorkflowNodeUpsertParam nodeParam = new WorkflowNodeUpsertParam();
      nodeParam.setWorkflowDefinitionId(definitionId);
      nodeParam.setNodeCode(node.nodeCode());
      nodeParam.setNodeName(node.nodeName());
      nodeParam.setNodeType(node.nodeType());
      nodeParam.setRelatedJobCode(node.relatedJobCode());
      nodeParam.setRelatedPipelineCode(node.relatedPipelineCode());
      nodeParam.setWorkerGroup(node.workerGroup());
      nodeParam.setWindowCode(node.windowCode());
      nodeParam.setNodeOrder(node.nodeOrder());
      nodeParam.setRetryPolicy(node.retryPolicy());
      nodeParam.setRetryMaxCount(node.retryMaxCount());
      nodeParam.setTimeoutSeconds(node.timeoutSeconds());
      nodeParam.setNodeParams(node.nodeParams());
      nodeParam.setEnabled(node.enabled());
      workflowNodeMapper.upsertWorkflowNode(nodeParam);
      if (existingNode == null) {
        counters.insertedNodes++;
      } else {
        counters.updatedNodes++;
      }
    }
  }

  private void applyEdges(
      Long definitionId, List<WorkflowEdgeRow> workflowEdges, ApplyCounters counters) {
    for (WorkflowEdgeRow edge : workflowEdges) {
      WorkflowEdgeEntity existingEdge =
          workflowEdgeMapper.selectByUniqueKey(
              definitionId, edge.fromNodeCode(), edge.toNodeCode(), edge.edgeType());
      WorkflowEdgeUpsertParam edgeParam = new WorkflowEdgeUpsertParam();
      edgeParam.setWorkflowDefinitionId(definitionId);
      edgeParam.setFromNodeCode(edge.fromNodeCode());
      edgeParam.setToNodeCode(edge.toNodeCode());
      edgeParam.setEdgeType(edge.edgeType());
      edgeParam.setConditionExpr(edge.conditionExpr());
      edgeParam.setEnabled(edge.enabled());
      workflowEdgeMapper.upsertWorkflowEdge(edgeParam);
      if (existingEdge == null) {
        counters.insertedEdges++;
      } else {
        counters.updatedEdges++;
      }
    }
  }

  private static class ApplyCounters {
    int insertedDefinitions;
    int updatedDefinitions;
    int insertedNodes;
    int updatedNodes;
    int insertedEdges;
    int updatedEdges;
  }

  private ParsedSession loadSession(String uploadToken) {
    WorkflowExcelSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return new ParsedSession(
        session.fileName(),
        session.tenantId(),
        session.uploadedAt(),
        session.definitions(),
        session.nodes(),
        session.edges());
  }

  private Sheet findSheet(Workbook workbook, String sheetName) {
    Sheet sheet = workbook.getSheet(sheetName);
    Guard.require(sheet != null, "excel sheet missing: " + sheetName);
    return sheet;
  }

  private List<WorkflowDefinitionRow> parseDefinitionSheet(Sheet sheet, String tenantId) {
    List<WorkflowDefinitionRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet, DEF_COLUMNS, DEF_HEADERS)) {
      Map<String, String> rowValues = rowData.values();
      rows.add(
          new WorkflowDefinitionRow(
              new WorkflowIdentity(
                  rowData.rowNo(),
                  tenantOrDefault(rowValues.get(COL_TENANT_ID), tenantId),
                  normalize(rowValues.get(COL_WORKFLOW_CODE))),
              new WorkflowDefinitionPayload(
                  normalize(rowValues.get("workflow_name")),
                  normalizeEnum(rowValues.get(COL_WORKFLOW_TYPE), WORKFLOW_TYPES),
                  parseInteger(rowValues.get("version")),
                  parseBoolean(rowValues.get(COL_ENABLED), true),
                  normalize(rowValues.get(COL_DESCRIPTION)))));
    }
    return rows;
  }

  private List<WorkflowNodeRow> parseNodeSheet(Sheet sheet, String tenantId) {
    List<WorkflowNodeRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet, NODE_COLUMNS, NODE_HEADERS)) {
      Map<String, String> rowValues = rowData.values();
      rows.add(
          new WorkflowNodeRow(
              new WorkflowNodeIdentity(
                  rowData.rowNo(),
                  tenantOrDefault(rowValues.get(COL_TENANT_ID), tenantId),
                  normalize(rowValues.get(COL_WORKFLOW_CODE)),
                  parseInteger(rowValues.get(COL_WORKFLOW_VERSION)),
                  normalize(rowValues.get("node_code"))),
              new WorkflowNodeRelation(
                  normalize(rowValues.get("node_name")),
                  normalizeEnum(rowValues.get(COL_NODE_TYPE), NODE_TYPES),
                  normalize(rowValues.get("related_job_code")),
                  normalize(rowValues.get("related_pipeline_code"))),
              new WorkflowNodeExecution(
                  normalize(rowValues.get("worker_group")),
                  normalize(rowValues.get("window_code")),
                  parseInteger(rowValues.get("node_order"))),
              new WorkflowNodeRuntime(
                  normalizeEnum(rowValues.get(COL_RETRY_POLICY), RETRY_POLICIES),
                  parseInteger(rowValues.get("retry_max_count")),
                  parseInteger(rowValues.get("timeout_seconds")),
                  normalize(rowValues.get("node_params")),
                  parseBoolean(rowValues.get(COL_ENABLED), true))));
    }
    return rows;
  }

  private List<WorkflowEdgeRow> parseEdgeSheet(Sheet sheet, String tenantId) {
    List<WorkflowEdgeRow> rows = new ArrayList<>();
    for (SheetRow rowData : readSheetRows(sheet, EDGE_COLUMNS, EDGE_HEADERS)) {
      Map<String, String> rowValues = rowData.values();
      rows.add(
          new WorkflowEdgeRow(
              new WorkflowEdgeIdentity(
                  rowData.rowNo(),
                  tenantOrDefault(rowValues.get(COL_TENANT_ID), tenantId),
                  normalize(rowValues.get(COL_WORKFLOW_CODE)),
                  parseInteger(rowValues.get(COL_WORKFLOW_VERSION)),
                  normalize(rowValues.get("from_node_code")),
                  normalize(rowValues.get("to_node_code"))),
              new WorkflowEdgePayload(
                  normalizeEnum(rowValues.get(COL_EDGE_TYPE), EDGE_TYPES),
                  normalize(rowValues.get("condition_expr")),
                  parseBoolean(rowValues.get(COL_ENABLED), true))));
    }
    return rows;
  }

  private List<SheetRow> readSheetRows(
      Sheet sheet, List<String> columns, Set<String> requiredHeaders) {
    DataFormatter formatter = new DataFormatter();
    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
    if (headerRow == null) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "excel header row is missing for sheet: " + sheet.getSheetName());
    }
    Map<String, Integer> headerIndex = readHeaderIndex(headerRow, formatter);
    validateHeaders(sheet.getSheetName(), headerIndex, requiredHeaders);
    List<SheetRow> rows = new ArrayList<>();
    for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || rowIsBlank(row, formatter)) {
        continue;
      }
      Map<String, String> rowValues = new LinkedHashMap<>();
      for (String header : columns) {
        Integer columnIndex = headerIndex.get(header);
        rowValues.put(header, normalize(cellText(row, columnIndex, formatter)));
      }
      rows.add(new SheetRow(row.getRowNum() + 1, rowValues));
    }
    return rows;
  }

  private ValidationResult validate(ParsedSession session) {
    List<ConsoleWorkflowExcelRowIssueResponse> issues = new ArrayList<>();
    Set<WorkflowKey> definitionKeys = new LinkedHashSet<>();
    List<WorkflowDefinitionRow> validDefinitions =
        validateWorkflowStructure(session.definitions(), definitionKeys, issues);
    List<WorkflowNodeRow> validNodes = validateNodes(session.nodes(), definitionKeys, issues);
    List<WorkflowEdgeRow> validEdges = validateEdges(session.edges(), definitionKeys, issues);

    int totalRows = session.definitions().size() + session.nodes().size() + session.edges().size();
    int validRows = validDefinitions.size() + validNodes.size() + validEdges.size();
    return ValidationResult.builder()
        .counts(
            ValidationCounts.builder()
                .definitionRows(session.definitions().size())
                .nodeRows(session.nodes().size())
                .edgeRows(session.edges().size())
                .totalRows(totalRows)
                .validRows(validRows)
                .invalidRows(totalRows - validRows)
                .build())
        .data(
            ValidationData.builder()
                .definitions(validDefinitions)
                .nodes(validNodes)
                .edges(validEdges)
                .issues(issues)
                .build())
        .build();
  }

  private List<WorkflowDefinitionRow> validateWorkflowStructure(
      List<WorkflowDefinitionRow> definitions,
      Set<WorkflowKey> definitionKeys,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkflowDefinitionRow> valid = new ArrayList<>();
    for (WorkflowDefinitionRow row : definitions) {
      List<String> rowIssues = new ArrayList<>();
      WorkflowKey key = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.version());
      if (!hasText(row.tenantId())
          || !hasText(row.workflowCode())
          || !hasText(row.workflowName())
          || !hasText(row.workflowType())
          || row.version() == null) {
        rowIssues.add("workflow definition fields are incomplete");
      }
      if (hasText(row.workflowType()) && !WORKFLOW_TYPES.contains(row.workflowType())) {
        rowIssues.add("workflow_type must be one of " + WORKFLOW_TYPES);
      }
      if (!definitionKeys.add(key)) {
        rowIssues.add("duplicate workflow definition in excel: " + key.display());
      }
      if (rowIssues.isEmpty()) {
        valid.add(row);
      } else {
        issues.add(
            new ConsoleWorkflowExcelRowIssueResponse(
                DEF_SHEET,
                row.rowNo(),
                key.display(),
                row.workflowCode(),
                row.version(),
                List.copyOf(rowIssues)));
      }
    }
    return valid;
  }

  private List<WorkflowNodeRow> validateNodes(
      List<WorkflowNodeRow> nodes,
      Set<WorkflowKey> definitionKeys,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkflowNodeRow> valid = new ArrayList<>();
    Set<NodeKey> nodeKeys = new LinkedHashSet<>();
    for (WorkflowNodeRow row : nodes) {
      List<String> rowIssues = new ArrayList<>();
      WorkflowKey workflowKey =
          WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion());
      if (!definitionKeys.contains(workflowKey)) {
        rowIssues.add("workflow node references missing definition: " + workflowKey.display());
      }
      if (!hasText(row.tenantId())
          || !hasText(row.workflowCode())
          || row.workflowVersion() == null
          || !hasText(row.nodeCode())
          || !hasText(row.nodeName())
          || !hasText(row.nodeType())) {
        rowIssues.add("workflow node fields are incomplete");
      }
      if (hasText(row.nodeType()) && !NODE_TYPES.contains(row.nodeType())) {
        rowIssues.add("node_type must be one of " + NODE_TYPES);
      }
      if (hasText(row.retryPolicy()) && !RETRY_POLICIES.contains(row.retryPolicy())) {
        rowIssues.add("retry_policy must be one of " + RETRY_POLICIES);
      }
      if (row.nodeParams() != null) {
        try {
          JsonUtils.fromJson(row.nodeParams(), Object.class);
        } catch (IllegalArgumentException exception) {
          rowIssues.add("node_params must be valid JSON");
        }
      }
      NodeKey nodeKey = NodeKey.of(workflowKey, row.nodeCode());
      if (!nodeKeys.add(nodeKey)) {
        rowIssues.add("duplicate workflow node in excel: " + nodeKey.display());
      }
      if (rowIssues.isEmpty()) {
        valid.add(row);
      } else {
        issues.add(
            new ConsoleWorkflowExcelRowIssueResponse(
                NODE_SHEET,
                row.rowNo(),
                nodeKey.display(),
                row.workflowCode(),
                row.workflowVersion(),
                List.copyOf(rowIssues)));
      }
    }
    return valid;
  }

  private List<WorkflowEdgeRow> validateEdges(
      List<WorkflowEdgeRow> edges,
      Set<WorkflowKey> definitionKeys,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkflowEdgeRow> valid = new ArrayList<>();
    Set<EdgeKey> edgeKeys = new LinkedHashSet<>();
    for (WorkflowEdgeRow row : edges) {
      List<String> rowIssues = new ArrayList<>();
      WorkflowKey workflowKey =
          WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion());
      if (!definitionKeys.contains(workflowKey)) {
        rowIssues.add("workflow edge references missing definition: " + workflowKey.display());
      }
      if (!hasText(row.tenantId())
          || !hasText(row.workflowCode())
          || row.workflowVersion() == null
          || !hasText(row.fromNodeCode())
          || !hasText(row.toNodeCode())
          || !hasText(row.edgeType())) {
        rowIssues.add("workflow edge fields are incomplete");
      }
      if (hasText(row.edgeType()) && !EDGE_TYPES.contains(row.edgeType())) {
        rowIssues.add("edge_type must be one of " + EDGE_TYPES);
      }
      EdgeKey edgeKey =
          EdgeKey.of(workflowKey, row.fromNodeCode(), row.toNodeCode(), row.edgeType());
      if (!edgeKeys.add(edgeKey)) {
        rowIssues.add("duplicate workflow edge in excel: " + edgeKey.display());
      }
      if (rowIssues.isEmpty()) {
        valid.add(row);
      } else {
        issues.add(
            new ConsoleWorkflowExcelRowIssueResponse(
                EDGE_SHEET,
                row.rowNo(),
                edgeKey.display(),
                row.workflowCode(),
                row.workflowVersion(),
                List.copyOf(rowIssues)));
      }
    }
    return valid;
  }

  private byte[] writePreviewWorkbook(ParsedSession session, ValidationResult validationResult) {
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet definitionSheet = workbook.createSheet(DEF_SHEET);
      Sheet nodeSheet = workbook.createSheet(NODE_SHEET);
      Sheet edgeSheet = workbook.createSheet(EDGE_SHEET);
      definitionSheet.createFreezePane(0, 1, 0, 1);
      nodeSheet.createFreezePane(0, 1, 0, 1);
      edgeSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(definitionSheet, DEF_COLUMNS, DEF_COLUMN_GUIDES, workbook);
      writeTemplateHeaders(nodeSheet, NODE_COLUMNS, NODE_COLUMN_GUIDES, workbook);
      writeTemplateHeaders(edgeSheet, EDGE_COLUMNS, EDGE_COLUMN_GUIDES, workbook);

      populatePreviewDefinitionSheet(definitionSheet, session.definitions());
      populatePreviewNodeSheet(nodeSheet, session.nodes());
      populatePreviewEdgeSheet(edgeSheet, session.edges());

      applyValidations(definitionSheet, nodeSheet, edgeSheet);
      setWidths(definitionSheet, DEF_COLUMNS);
      setWidths(nodeSheet, NODE_COLUMNS);
      setWidths(edgeSheet, EDGE_COLUMNS);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);

      populatePreviewIssueAnnotations(
          workbook, definitionSheet, nodeSheet, edgeSheet, validationResult);
      return ConsoleExcelPreviewWorkbookSupport.toBytes(workbook);
    } catch (IOException exception) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate preview excel workbook");
    }
  }

  private void populatePreviewDefinitionSheet(
      Sheet definitionSheet, List<WorkflowDefinitionRow> definitions) {
    int rowIndex = 1;
    for (WorkflowDefinitionRow rowData : definitions) {
      Row row = definitionSheet.createRow(rowIndex++);
      writeCell(row, 0, rowData.tenantId());
      writeCell(row, 1, rowData.workflowCode());
      writeCell(row, 2, rowData.workflowName());
      writeCell(row, 3, rowData.workflowType());
      writeCell(row, 4, rowData.version());
      writeCell(row, 5, rowData.enabled());
      writeCell(row, 6, rowData.description());
    }
  }

  private void populatePreviewNodeSheet(Sheet nodeSheet, List<WorkflowNodeRow> nodes) {
    int rowIndex = 1;
    for (WorkflowNodeRow rowData : nodes) {
      Row row = nodeSheet.createRow(rowIndex++);
      writeCell(row, 0, rowData.tenantId());
      writeCell(row, 1, rowData.workflowCode());
      writeCell(row, 2, rowData.workflowVersion());
      writeCell(row, 3, rowData.nodeCode());
      writeCell(row, 4, rowData.nodeName());
      writeCell(row, 5, rowData.nodeType());
      writeCell(row, 6, rowData.relatedJobCode());
      writeCell(row, 7, rowData.relatedPipelineCode());
      writeCell(row, 8, rowData.workerGroup());
      writeCell(row, 9, rowData.windowCode());
      writeCell(row, 10, rowData.nodeOrder());
      writeCell(row, 11, rowData.retryPolicy());
      writeCell(row, 12, rowData.retryMaxCount());
      writeCell(row, 13, rowData.timeoutSeconds());
      writeCell(row, 14, rowData.nodeParams());
      writeCell(row, 15, rowData.enabled());
    }
  }

  private void populatePreviewEdgeSheet(Sheet edgeSheet, List<WorkflowEdgeRow> edges) {
    int rowIndex = 1;
    for (WorkflowEdgeRow rowData : edges) {
      Row row = edgeSheet.createRow(rowIndex++);
      writeCell(row, 0, rowData.tenantId());
      writeCell(row, 1, rowData.workflowCode());
      writeCell(row, 2, rowData.workflowVersion());
      writeCell(row, 3, rowData.fromNodeCode());
      writeCell(row, 4, rowData.toNodeCode());
      writeCell(row, 5, rowData.edgeType());
      writeCell(row, 6, rowData.conditionExpr());
      writeCell(row, 7, rowData.enabled());
    }
  }

  private void populatePreviewIssueAnnotations(
      Workbook workbook,
      Sheet definitionSheet,
      Sheet nodeSheet,
      Sheet edgeSheet,
      ValidationResult validationResult) {
    List<WorkbookIssue> workbookIssues =
        validationResult.issues().stream()
            .flatMap(
                issue ->
                    ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        issue.sheetName(),
                        issue.rowNo(),
                        issue.messages(),
                        columnsForSheet(issue.sheetName()))
                        .stream())
            .toList();
    ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(workbook, workbookIssues);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(
        definitionSheet, DEF_COLUMNS, filterIssues(workbookIssues, DEF_SHEET), 1);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(
        nodeSheet, NODE_COLUMNS, filterIssues(workbookIssues, NODE_SHEET), 3);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(
        edgeSheet, EDGE_COLUMNS, filterIssues(workbookIssues, EDGE_SHEET), 3);
  }

  private byte[] writeWorkbook(String tenantId, List<WorkflowDefinitionEntity> definitions) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet definitionSheet = workbook.createSheet(DEF_SHEET);
      Sheet nodeSheet = workbook.createSheet(NODE_SHEET);
      Sheet edgeSheet = workbook.createSheet(EDGE_SHEET);
      definitionSheet.createFreezePane(0, 1, 0, 1);
      nodeSheet.createFreezePane(0, 1, 0, 1);
      edgeSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(definitionSheet, DEF_COLUMNS, DEF_COLUMN_GUIDES, workbook);
      writeTemplateHeaders(nodeSheet, NODE_COLUMNS, NODE_COLUMN_GUIDES, workbook);
      writeTemplateHeaders(edgeSheet, EDGE_COLUMNS, EDGE_COLUMN_GUIDES, workbook);

      writeDefinitionSheet(definitionSheet, definitions);
      int nodeRowIndex = 1;
      int edgeRowIndex = 1;
      for (WorkflowDefinitionEntity definition : definitions) {
        nodeRowIndex = writeNodeSheet(nodeSheet, tenantId, definition, nodeRowIndex);
        edgeRowIndex = writeEdgeSheet(edgeSheet, tenantId, definition, edgeRowIndex);
      }

      applyValidations(definitionSheet, nodeSheet, edgeSheet);
      setWidths(definitionSheet, DEF_COLUMNS);
      setWidths(nodeSheet, NODE_COLUMNS);
      setWidths(edgeSheet, EDGE_COLUMNS);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate excel workbook");
    }
  }

  private void writeDefinitionSheet(Sheet sheet, List<WorkflowDefinitionEntity> definitions) {
    int rowIndex = 1;
    for (WorkflowDefinitionEntity definition : definitions) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, definition.getTenantId());
      writeCell(row, 1, definition.getWorkflowCode());
      writeCell(row, 2, definition.getWorkflowName());
      writeCell(row, 3, definition.getWorkflowType());
      writeCell(row, 4, definition.getVersion());
      writeCell(row, 5, definition.getEnabled());
      writeCell(row, 6, definition.getDescription());
    }
  }

  private int writeNodeSheet(
      Sheet sheet, String tenantId, WorkflowDefinitionEntity definition, int startRowIndex) {
    List<WorkflowNodeEntity> nodes =
        workflowNodeMapper.selectByQuery(
            new WorkflowNodeQuery(
                tenantId,
                definition.getId(),
                definition.getWorkflowCode(),
                null,
                null,
                null,
                null));
    int rowIndex = startRowIndex;
    for (WorkflowNodeEntity node : nodes) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, tenantId);
      writeCell(row, 1, definition.getWorkflowCode());
      writeCell(row, 2, definition.getVersion());
      writeCell(row, 3, node.getNodeCode());
      writeCell(row, 4, node.getNodeName());
      writeCell(row, 5, node.getNodeType());
      writeCell(row, 6, node.getRelatedJobCode());
      writeCell(row, 7, node.getRelatedPipelineCode());
      writeCell(row, 8, node.getWorkerGroup());
      writeCell(row, 9, node.getWindowCode());
      writeCell(row, 10, node.getNodeOrder());
      writeCell(row, 11, node.getRetryPolicy());
      writeCell(row, 12, node.getRetryMaxCount());
      writeCell(row, 13, node.getTimeoutSeconds());
      writeCell(row, 14, node.getNodeParams());
      writeCell(row, 15, node.getEnabled());
    }
    return rowIndex;
  }

  private int writeEdgeSheet(
      Sheet sheet, String tenantId, WorkflowDefinitionEntity definition, int startRowIndex) {
    List<WorkflowEdgeEntity> edges =
        workflowEdgeMapper.selectByQuery(
            new WorkflowEdgeQuery(
                tenantId,
                definition.getId(),
                definition.getWorkflowCode(),
                null,
                null,
                null,
                null,
                null));
    int rowIndex = startRowIndex;
    for (WorkflowEdgeEntity edge : edges) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, tenantId);
      writeCell(row, 1, definition.getWorkflowCode());
      writeCell(row, 2, definition.getVersion());
      writeCell(row, 3, edge.getFromNodeCode());
      writeCell(row, 4, edge.getToNodeCode());
      writeCell(row, 5, edge.getEdgeType());
      writeCell(row, 6, edge.getConditionExpr());
      writeCell(row, 7, edge.getEnabled());
    }
    return rowIndex;
  }

  private void applyValidations(Sheet definitionSheet, Sheet nodeSheet, Sheet edgeSheet) {
    addDropdownValidation(
        definitionSheet,
        3,
        WORKFLOW_TYPES.toArray(String[]::new),
        "workflow_type 填写提示",
        "请从下拉列表中选择 DAG、PIPELINE 或 MIXED。");
    addBooleanValidation(definitionSheet, new int[] {5}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
    addDropdownValidation(
        nodeSheet, 5, NODE_TYPES.toArray(String[]::new), "node_type 填写提示", "请从下拉列表中选择节点类型。");
    addDropdownValidation(
        nodeSheet,
        11,
        RETRY_POLICIES.toArray(String[]::new),
        "retry_policy 填写提示",
        "请从下拉列表中选择重试策略。");
    addBooleanValidation(nodeSheet, new int[] {15}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
    addDropdownValidation(
        edgeSheet, 5, EDGE_TYPES.toArray(String[]::new), "edge_type 填写提示", "请从下拉列表中选择边类型。");
    addBooleanValidation(edgeSheet, new int[] {7}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "workflow definition / node / edge maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. The workbook must keep the sheet order: definition, node, edge, README, DICT,"
          + " VALIDATION.",
      "3. workflow_code + version is the cross-sheet key for definitions, nodes, and edges.",
      "4. node_params must stay valid JSON. CONDITION edges can use condition_expr.",
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
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_WORKFLOW_TYPE, "DAG", "dag workflow"},
      {COL_WORKFLOW_TYPE, "PIPELINE", "pipeline workflow"},
      {COL_WORKFLOW_TYPE, "MIXED", "mixed workflow"},
      {COL_NODE_TYPE, "TASK", "task node"},
      {COL_NODE_TYPE, "GATEWAY", "gateway node"},
      {COL_NODE_TYPE, "FILE_STEP", "file step node"},
      {COL_NODE_TYPE, "START", "start node"},
      {COL_NODE_TYPE, "END", "end node"},
      {COL_NODE_TYPE, "JOB", "job node"},
      {COL_RETRY_POLICY, "NONE", "no retry"},
      {COL_RETRY_POLICY, "FIXED", "fixed retry"},
      {COL_RETRY_POLICY, "EXPONENTIAL", "exponential retry"},
      {COL_EDGE_TYPE, EDGE_SUCCESS, "success edge"},
      {COL_EDGE_TYPE, "FAILURE", "failure edge"},
      {COL_EDGE_TYPE, "CONDITION", "condition edge"},
      {COL_EDGE_TYPE, "ALWAYS", "always edge"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, GUIDE_FALSE, "disabled"}
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

  private List<String> columnsForSheet(String sheetName) {
    if (NODE_SHEET.equals(sheetName)) {
      return NODE_COLUMNS;
    }
    if (EDGE_SHEET.equals(sheetName)) {
      return EDGE_COLUMNS;
    }
    return DEF_COLUMNS;
  }

  private List<WorkbookIssue> filterIssues(List<WorkbookIssue> issues, String sheetName) {
    return issues.stream().filter(issue -> Objects.equals(sheetName, issue.sheetName())).toList();
  }

  private record DefinitionChangeContext(
      WorkflowDefinitionRow row,
      int nodeCount,
      int edgeCount,
      String reason,
      String operatorId,
      String traceId,
      String action) {}

  private void logDefinitionChange(DefinitionChangeContext ctx) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId", ctx.row().tenantId(),
            "configType", "WORKFLOW_DEFINITION",
            "configKey", ctx.row().workflowCode() + "#" + ctx.row().version(),
            "versionNo", ctx.row().version(),
            "changeAction", ctx.action(),
            "changeResult", EDGE_SUCCESS,
            "operatorType", "USER",
            "operatorId", ConsoleTextSanitizer.safeInput(ctx.operatorId(), 64),
            "traceId", ConsoleTextSanitizer.safeInput(ctx.traceId(), 128),
            "changeSummaryJson",
                JsonUtils.toJson(
                    mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(ctx.reason(), 512),
                        "detail",
                            mapOf(
                                "workflowName",
                                ctx.row().workflowName(),
                                "workflowType",
                                ctx.row().workflowType(),
                                COL_ENABLED,
                                ctx.row().enabled(),
                                "nodeCount",
                                ctx.nodeCount(),
                                "edgeCount",
                                ctx.edgeCount())))));
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
      return "workflow-maintenance.xlsx";
    }
    return fileName;
  }

  private ConsoleWorkflowDefinitionExcelRowResponse toResponse(WorkflowDefinitionRow row) {
    return new ConsoleWorkflowDefinitionExcelRowResponse(
        row.tenantId(),
        row.workflowCode(),
        row.workflowName(),
        row.workflowType(),
        row.version(),
        row.enabled(),
        row.description());
  }

  private ConsoleWorkflowNodeExcelRowResponse toResponse(WorkflowNodeRow row) {
    return new ConsoleWorkflowNodeExcelRowResponse(
        row.tenantId(),
        row.workflowCode(),
        row.workflowVersion(),
        row.nodeCode(),
        row.nodeName(),
        row.nodeType(),
        row.relatedJobCode(),
        row.relatedPipelineCode(),
        row.workerGroup(),
        row.windowCode(),
        row.nodeOrder(),
        row.retryPolicy(),
        row.retryMaxCount(),
        row.timeoutSeconds(),
        row.nodeParams(),
        row.enabled());
  }

  private ConsoleWorkflowEdgeExcelRowResponse toResponse(WorkflowEdgeRow row) {
    return new ConsoleWorkflowEdgeExcelRowResponse(
        row.tenantId(),
        row.workflowCode(),
        row.workflowVersion(),
        row.fromNodeCode(),
        row.toNodeCode(),
        row.edgeType(),
        row.conditionExpr(),
        row.enabled());
  }

  private String normalize(String value) {
    return ConsoleTextSanitizer.normalize(value);
  }

  private Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
    Map<String, Integer> headers = new LinkedHashMap<>();
    for (int cellIndex = headerRow.getFirstCellNum();
        cellIndex < headerRow.getLastCellNum();
        cellIndex++) {
      Cell cell = headerRow.getCell(cellIndex);
      String header = normalize(formatter.formatCellValue(cell));
      if (StringUtils.hasText(header)) {
        headers.put(header, cellIndex);
      }
    }
    return headers;
  }

  private void validateHeaders(
      String sheetName, Map<String, Integer> headerIndex, Set<String> requiredHeaders) {
    Set<String> missing = new LinkedHashSet<>(requiredHeaders);
    missing.removeAll(headerIndex.keySet());
    if (!missing.isEmpty()) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
          "excel header missing for sheet " + sheetName + ": " + String.join(", ", missing));
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

  private String tenantOrDefault(String value, String tenantId) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized : tenantId;
  }

  private boolean hasText(String value) {
    return StringUtils.hasText(normalize(value));
  }

  private String normalizeEnum(String value, Set<String> allowed) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    return allowed.contains(upper) ? upper : upper;
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
    if (List.of(GUIDE_TRUE, "Y", "1", "YES").contains(upper)) {
      return true;
    }
    if (List.of(GUIDE_FALSE, "N", "0", "NO").contains(upper)) {
      return false;
    }
    return defaultValue;
  }

  private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName)
      throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
      }
      List<WorkflowDefinitionRow> definitions =
          parseDefinitionSheet(findSheet(workbook, DEF_SHEET), tenantId);
      List<WorkflowNodeRow> nodes = parseNodeSheet(findSheet(workbook, NODE_SHEET), tenantId);
      List<WorkflowEdgeRow> edges = parseEdgeSheet(findSheet(workbook, EDGE_SHEET), tenantId);
      return new ParsedWorkbook(
          fileNameOrDefault(originalFileName), tenantId, definitions, nodes, edges);
    } catch (BizException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + exception.getMessage());
    }
  }

  private Map<WorkflowKey, List<WorkflowNodeRow>> groupNodes(List<WorkflowNodeRow> rows) {
    Map<WorkflowKey, List<WorkflowNodeRow>> grouped = new LinkedHashMap<>();
    for (WorkflowNodeRow row : rows) {
      grouped
          .computeIfAbsent(
              WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion()),
              key -> new ArrayList<>())
          .add(row);
    }
    return grouped;
  }

  private Map<WorkflowKey, List<WorkflowEdgeRow>> groupEdges(List<WorkflowEdgeRow> rows) {
    Map<WorkflowKey, List<WorkflowEdgeRow>> grouped = new LinkedHashMap<>();
    for (WorkflowEdgeRow row : rows) {
      grouped
          .computeIfAbsent(
              WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion()),
              key -> new ArrayList<>())
          .add(row);
    }
    return grouped;
  }

  private record ParsedWorkbook(
      String fileName,
      String tenantId,
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges) {}

  private record ParsedSession(
      String fileName,
      String tenantId,
      Instant uploadedAt,
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges) {}

  @Builder
  private record ValidationResult(ValidationCounts counts, ValidationData data) {
    int definitionRows() {
      return counts.definitionRows();
    }

    int nodeRows() {
      return counts.nodeRows();
    }

    int edgeRows() {
      return counts.edgeRows();
    }

    int totalRows() {
      return counts.totalRows();
    }

    int validRows() {
      return counts.validRows();
    }

    int invalidRows() {
      return counts.invalidRows();
    }

    List<WorkflowDefinitionRow> definitions() {
      return data.definitions();
    }

    List<WorkflowNodeRow> nodes() {
      return data.nodes();
    }

    List<WorkflowEdgeRow> edges() {
      return data.edges();
    }

    List<ConsoleWorkflowExcelRowIssueResponse> issues() {
      return data.issues();
    }
  }

  @Builder
  private record ValidationCounts(
      int definitionRows,
      int nodeRows,
      int edgeRows,
      int totalRows,
      int validRows,
      int invalidRows) {}

  @Builder
  private record ValidationData(
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {}

  private record WorkflowKey(String tenantId, String workflowCode, Integer version) {
    static WorkflowKey of(String tenantId, String workflowCode, Integer version) {
      return new WorkflowKey(tenantId, workflowCode, version);
    }

    String display() {
      return workflowCode + "#" + version;
    }
  }

  private record NodeKey(WorkflowKey workflowKey, String nodeCode) {
    static NodeKey of(WorkflowKey workflowKey, String nodeCode) {
      return new NodeKey(workflowKey, nodeCode);
    }

    String display() {
      return workflowKey.display() + "/" + nodeCode;
    }
  }

  private record EdgeKey(
      WorkflowKey workflowKey, String fromNodeCode, String toNodeCode, String edgeType) {
    static EdgeKey of(
        WorkflowKey workflowKey, String fromNodeCode, String toNodeCode, String edgeType) {
      return new EdgeKey(workflowKey, fromNodeCode, toNodeCode, edgeType);
    }

    String display() {
      return workflowKey.display() + "/" + fromNodeCode + "->" + toNodeCode + "(" + edgeType + ")";
    }
  }

  private record SheetRow(int rowNo, Map<String, String> values) {}
}
