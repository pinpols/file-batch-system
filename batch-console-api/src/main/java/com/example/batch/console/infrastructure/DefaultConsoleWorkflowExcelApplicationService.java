package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_DESCRIPTION;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_EDGE_TYPE;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_ENABLED;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_NODE_TYPE;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_RETRY_POLICY;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_TENANT_ID;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_WORKFLOW_CODE;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_WORKFLOW_TYPE;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_WORKFLOW_VERSION;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.DEF_COLUMNS;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.DEF_HEADERS;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.DEF_SHEET;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.EDGE_COLUMNS;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.EDGE_HEADERS;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.EDGE_SHEET;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.GUIDE_FALSE;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.GUIDE_TRUE;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.NODE_COLUMNS;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.NODE_HEADERS;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.NODE_SHEET;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.ConsoleWorkflowExcelApplicationService;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.param.WorkflowDefinitionUpsertParam;
import com.example.batch.console.mapper.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.mapper.param.WorkflowNodeUpsertParam;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link com.example.batch.console.application.ConsoleWorkflowExcelApplicationService} 的默认实现（POI
 * 解析/生成与内存会话）。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowExcelApplicationService
    implements ConsoleWorkflowExcelApplicationService {

  private static final Set<String> WORKFLOW_TYPES = DictEnum.codes(WorkflowType.class);
  private static final Set<String> NODE_TYPES = DictEnum.codes(WorkflowNodeType.class);
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  private static final Set<String> EDGE_TYPES = DictEnum.codes(WorkflowEdgeType.class);

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final WorkflowExcelImportStore importStore;
  private final WorkflowExcelWorkbookWriter workbookWriter;

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
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(tenantId, definitions);
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
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook("template", List.of());
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
    byte[] workbookBytes =
        workbookWriter.writePreviewWorkbook(
            session.definitions(), session.nodes(), session.edges(), validationResult.issues());
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
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_workflow_rows");
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
        throw BizException.of(ResultCode.SYSTEM_ERROR, "error.workflow.resolve_definition_failed");
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
                  CodeNormalizer.toUpperOrNull(rowValues.get("worker_group")),
                  CodeNormalizer.toConfigFormOrNull(rowValues.get("window_code")),
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
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
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
        ConfigChangeLogBuilder.create(ctx.row().tenantId(), ctx.operatorId(), ctx.traceId())
            .forType("WORKFLOW_DEFINITION")
            .withKey(ctx.row().workflowCode() + "#" + ctx.row().version())
            .versionNo(ctx.row().version())
            .action(ctx.action())
            .summary(
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
                                ctx.edgeCount()))))
            .build());
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      values.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return values;
  }

  private String fileNameOrDefault(String fileName) {
    if (!Texts.hasText(fileName)) {
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
      if (Texts.hasText(header)) {
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
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "excel header missing for sheet " + sheetName + ": " + String.join(", ", missing));
    }
  }

  private boolean rowIsBlank(Row row, DataFormatter formatter) {
    for (int cellIndex = row.getFirstCellNum(); cellIndex < row.getLastCellNum(); cellIndex++) {
      String value = normalize(formatter.formatCellValue(row.getCell(cellIndex)));
      if (Texts.hasText(value)) {
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
    return Texts.hasText(normalized) ? normalized : tenantId;
  }

  private boolean hasText(String value) {
    return Texts.hasText(normalize(value));
  }

  private String normalizeEnum(String value, Set<String> allowed) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    return allowed.contains(upper) ? upper : upper;
  }

  private Integer parseInteger(String value) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
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
    if (!Texts.hasText(normalized)) {
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
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.no_sheet");
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
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "failed to read excel workbook: " + exception.getMessage());
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

    // 显式覆写与 record 默认生成逻辑一致，仅为绕过 Alibaba 插件对 record 的识别盲区。
    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof WorkflowKey that)) {
        return false;
      }
      return Objects.equals(tenantId, that.tenantId)
          && Objects.equals(workflowCode, that.workflowCode)
          && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tenantId, workflowCode, version);
    }
  }

  private record NodeKey(WorkflowKey workflowKey, String nodeCode) {
    static NodeKey of(WorkflowKey workflowKey, String nodeCode) {
      return new NodeKey(workflowKey, nodeCode);
    }

    String display() {
      return workflowKey.display() + "/" + nodeCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof NodeKey that)) {
        return false;
      }
      return Objects.equals(workflowKey, that.workflowKey)
          && Objects.equals(nodeCode, that.nodeCode);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workflowKey, nodeCode);
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

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof EdgeKey that)) {
        return false;
      }
      return Objects.equals(workflowKey, that.workflowKey)
          && Objects.equals(fromNodeCode, that.fromNodeCode)
          && Objects.equals(toNodeCode, that.toNodeCode)
          && Objects.equals(edgeType, that.edgeType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workflowKey, fromNodeCode, toNodeCode, edgeType);
    }
  }

  private record SheetRow(int rowNo, Map<String, String> values) {}
}
