package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
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
import java.io.InputStream;
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
import lombok.Builder;
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
 * {@link com.example.batch.console.application.ConsoleWorkflowExcelApplicationService} 的默认实现（POI 解析/生成与内存会话）。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowExcelApplicationService implements ConsoleWorkflowExcelApplicationService {

    private static final String DEF_SHEET = "workflow_definition";
    private static final String NODE_SHEET = "workflow_node";
    private static final String EDGE_SHEET = "workflow_edge";

    private static final List<String> DEF_COLUMNS = List.of(
            "tenant_id",
            "workflow_code",
            "workflow_name",
            "workflow_type",
            "version",
            "enabled",
            "description"
    );
    private static final List<String> NODE_COLUMNS = List.of(
            "tenant_id",
            "workflow_code",
            "workflow_version",
            "node_code",
            "node_name",
            "node_type",
            "related_job_code",
            "related_pipeline_code",
            "worker_group",
            "window_code",
            "node_order",
            "retry_policy",
            "retry_max_count",
            "timeout_seconds",
            "node_params",
            "enabled"
    );
    private static final List<String> EDGE_COLUMNS = List.of(
            "tenant_id",
            "workflow_code",
            "workflow_version",
            "from_node_code",
            "to_node_code",
            "edge_type",
            "condition_expr",
            "enabled"
    );

    private static final Set<String> DEF_HEADERS = Set.copyOf(DEF_COLUMNS);
    private static final Set<String> NODE_HEADERS = Set.copyOf(NODE_COLUMNS);
    private static final Set<String> EDGE_HEADERS = Set.copyOf(EDGE_COLUMNS);

    private static final Set<String> WORKFLOW_TYPES = Set.of("DAG", "PIPELINE", "MIXED");
    private static final Set<String> NODE_TYPES = Set.of("TASK", "GATEWAY", "FILE_STEP", "START", "END", "JOB");
    private static final Set<String> RETRY_POLICIES = Set.of("NONE", "FIXED", "EXPONENTIAL");
    private static final Set<String> EDGE_TYPES = Set.of("SUCCESS", "FAILURE", "CONDITION", "ALWAYS");

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final WorkflowExcelImportStore importStore;

    @Override
    public ResponseEntity<InputStreamResource> exportWorkflowExcel(WorkflowDefinitionQueryRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectByQuery(new WorkflowDefinitionQuery(
                tenantId,
                request.getWorkflowCode(),
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled(),
                null
        ));
        byte[] workbookBytes = writeWorkbook(tenantId, definitions);
        InputStreamResource body = new InputStreamResource(new ByteArrayInputStream(workbookBytes));
        String fileName = "workflow-maintenance-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Override
    public ConsoleWorkflowExcelUploadResponse upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file is required");
        }
        String tenantId = tenantGuard.resolveTenant(null);
        ParsedWorkbook workbook = parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
        String uploadToken = importStore.save(workbook.fileName(), workbook.tenantId(), workbook.definitions(), workbook.nodes(), workbook.edges());
        return new ConsoleWorkflowExcelUploadResponse(
                uploadToken,
                workbook.fileName(),
                workbook.definitions().size(),
                workbook.nodes().size(),
                workbook.edges().size(),
                workbook.definitions().size() + workbook.nodes().size() + workbook.edges().size()
        );
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
                validationResult.issues()
        );
    }

    @Override
    @Transactional
    public ConsoleWorkflowExcelApplyResponse apply(String uploadToken, WorkflowExcelApplyRequest request) {
        ParsedSession session = loadSession(uploadToken);
        ValidationResult validationResult = validate(session);
        if (validationResult.invalidRows() > 0) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel contains invalid workflow rows");
        }
        ConsoleRequestMetadata metadata = requestMetadataResolver.current();
        String operatorId = metadata.operatorId();
        String traceId = metadata.traceId();
        Map<WorkflowKey, Long> definitionIds = new LinkedHashMap<>();
        int insertedDefinitions = 0;
        int updatedDefinitions = 0;
        int insertedNodes = 0;
        int updatedNodes = 0;
        int insertedEdges = 0;
        int updatedEdges = 0;

        Map<WorkflowKey, List<WorkflowNodeRow>> nodesByWorkflow = groupNodes(validationResult.nodes());
        Map<WorkflowKey, List<WorkflowEdgeRow>> edgesByWorkflow = groupEdges(validationResult.edges());

        for (WorkflowDefinitionRow row : validationResult.definitions()) {
            WorkflowKey key = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.version());
            WorkflowDefinitionEntity existing = workflowDefinitionMapper.selectByUniqueKey(row.tenantId(), row.workflowCode(), row.version());
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
            WorkflowDefinitionEntity saved = workflowDefinitionMapper.selectByUniqueKey(row.tenantId(), row.workflowCode(), row.version());
            if (saved == null || saved.getId() == null) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "failed to resolve workflow definition id");
            }
            definitionIds.put(key, saved.getId());
            if (existing == null) {
                insertedDefinitions++;
            } else {
                updatedDefinitions++;
            }
            List<WorkflowNodeRow> workflowNodes = nodesByWorkflow.getOrDefault(key, List.of());
            List<WorkflowEdgeRow> workflowEdges = edgesByWorkflow.getOrDefault(key, List.of());
            for (WorkflowNodeRow node : workflowNodes) {
                WorkflowNodeEntity existingNode = workflowNodeMapper.selectByUniqueKey(saved.getId(), node.nodeCode());
                WorkflowNodeUpsertParam nodeParam = new WorkflowNodeUpsertParam();
                nodeParam.setWorkflowDefinitionId(saved.getId());
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
                    insertedNodes++;
                } else {
                    updatedNodes++;
                }
            }
            for (WorkflowEdgeRow edge : workflowEdges) {
                WorkflowEdgeEntity existingEdge = workflowEdgeMapper.selectByUniqueKey(saved.getId(), edge.fromNodeCode(), edge.toNodeCode(), edge.edgeType());
                WorkflowEdgeUpsertParam edgeParam = new WorkflowEdgeUpsertParam();
                edgeParam.setWorkflowDefinitionId(saved.getId());
                edgeParam.setFromNodeCode(edge.fromNodeCode());
                edgeParam.setToNodeCode(edge.toNodeCode());
                edgeParam.setEdgeType(edge.edgeType());
                edgeParam.setConditionExpr(edge.conditionExpr());
                edgeParam.setEnabled(edge.enabled());
                workflowEdgeMapper.upsertWorkflowEdge(edgeParam);
                if (existingEdge == null) {
                    insertedEdges++;
                } else {
                    updatedEdges++;
                }
            }
            logDefinitionChange(new DefinitionChangeContext(row, workflowNodes.size(), workflowEdges.size(), request.getReason(), operatorId, traceId, existing == null ? "CREATE" : "PUBLISH"));
        }

        importStore.remove(uploadToken);
        return new ConsoleWorkflowExcelApplyResponse(
                uploadToken,
                session.tenantId(),
                validationResult.definitionRows(),
                validationResult.nodeRows(),
                validationResult.edgeRows(),
                insertedDefinitions,
                updatedDefinitions,
                insertedNodes,
                updatedNodes,
                insertedEdges,
                updatedEdges
        );
    }

    private ParsedSession loadSession(String uploadToken) {
        WorkflowExcelSession session = importStore.get(uploadToken);
        if (session == null) {
            throw new BizException(ResultCode.NOT_FOUND, "excel upload session not found");
        }
        tenantGuard.assertTenantAllowed(session.tenantId());
        return new ParsedSession(session.fileName(), session.tenantId(), session.uploadedAt(), session.definitions(), session.nodes(), session.edges());
    }

    private Sheet findSheet(Workbook workbook, String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel sheet missing: " + sheetName);
        }
        return sheet;
    }

    private List<WorkflowDefinitionRow> parseDefinitionSheet(Sheet sheet, String tenantId) {
        List<WorkflowDefinitionRow> rows = new ArrayList<>();
        for (SheetRow rowData : readSheetRows(sheet, DEF_COLUMNS, DEF_HEADERS)) {
            Map<String, String> rowValues = rowData.values();
            rows.add(new WorkflowDefinitionRow(
                    new WorkflowIdentity(
                            rowData.rowNo(),
                            tenantOrDefault(rowValues.get("tenant_id"), tenantId),
                            normalize(rowValues.get("workflow_code"))
                    ),
                    new WorkflowDefinitionPayload(
                            normalize(rowValues.get("workflow_name")),
                            normalizeEnum(rowValues.get("workflow_type"), WORKFLOW_TYPES),
                            parseInteger(rowValues.get("version")),
                            parseBoolean(rowValues.get("enabled"), true),
                            normalize(rowValues.get("description"))
                    )
            ));
        }
        return rows;
    }

    private List<WorkflowNodeRow> parseNodeSheet(Sheet sheet, String tenantId) {
        List<WorkflowNodeRow> rows = new ArrayList<>();
        for (SheetRow rowData : readSheetRows(sheet, NODE_COLUMNS, NODE_HEADERS)) {
            Map<String, String> rowValues = rowData.values();
            rows.add(new WorkflowNodeRow(
                    new WorkflowNodeIdentity(
                            rowData.rowNo(),
                            tenantOrDefault(rowValues.get("tenant_id"), tenantId),
                            normalize(rowValues.get("workflow_code")),
                            parseInteger(rowValues.get("workflow_version")),
                            normalize(rowValues.get("node_code"))
                    ),
                    new WorkflowNodeRelation(
                            normalize(rowValues.get("node_name")),
                            normalizeEnum(rowValues.get("node_type"), NODE_TYPES),
                            normalize(rowValues.get("related_job_code")),
                            normalize(rowValues.get("related_pipeline_code"))
                    ),
                    new WorkflowNodeExecution(
                            normalize(rowValues.get("worker_group")),
                            normalize(rowValues.get("window_code")),
                            parseInteger(rowValues.get("node_order"))
                    ),
                    new WorkflowNodeRuntime(
                            normalizeEnum(rowValues.get("retry_policy"), RETRY_POLICIES),
                            parseInteger(rowValues.get("retry_max_count")),
                            parseInteger(rowValues.get("timeout_seconds")),
                            normalize(rowValues.get("node_params")),
                            parseBoolean(rowValues.get("enabled"), true)
                    )
            ));
        }
        return rows;
    }

    private List<WorkflowEdgeRow> parseEdgeSheet(Sheet sheet, String tenantId) {
        List<WorkflowEdgeRow> rows = new ArrayList<>();
        for (SheetRow rowData : readSheetRows(sheet, EDGE_COLUMNS, EDGE_HEADERS)) {
            Map<String, String> rowValues = rowData.values();
            rows.add(new WorkflowEdgeRow(
                    new WorkflowEdgeIdentity(
                            rowData.rowNo(),
                            tenantOrDefault(rowValues.get("tenant_id"), tenantId),
                            normalize(rowValues.get("workflow_code")),
                            parseInteger(rowValues.get("workflow_version")),
                            normalize(rowValues.get("from_node_code")),
                            normalize(rowValues.get("to_node_code"))
                    ),
                    new WorkflowEdgePayload(
                            normalizeEnum(rowValues.get("edge_type"), EDGE_TYPES),
                            normalize(rowValues.get("condition_expr")),
                            parseBoolean(rowValues.get("enabled"), true)
                    )
            ));
        }
        return rows;
    }

    private List<SheetRow> readSheetRows(Sheet sheet, List<String> columns, Set<String> requiredHeaders) {
        DataFormatter formatter = new DataFormatter();
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel header row is missing for sheet: " + sheet.getSheetName());
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
        List<WorkflowDefinitionRow> validDefinitions = new ArrayList<>();
        List<WorkflowNodeRow> validNodes = new ArrayList<>();
        List<WorkflowEdgeRow> validEdges = new ArrayList<>();

        Set<WorkflowKey> definitionKeys = new LinkedHashSet<>();
        for (WorkflowDefinitionRow row : session.definitions()) {
            List<String> rowIssues = new ArrayList<>();
            WorkflowKey key = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.version());
            if (!hasText(row.tenantId()) || !hasText(row.workflowCode()) || !hasText(row.workflowName()) || !hasText(row.workflowType()) || row.version() == null) {
                rowIssues.add("workflow definition fields are incomplete");
            }
            if (hasText(row.workflowType()) && !WORKFLOW_TYPES.contains(row.workflowType())) {
                rowIssues.add("workflow_type must be one of " + WORKFLOW_TYPES);
            }
            if (!definitionKeys.add(key)) {
                rowIssues.add("duplicate workflow definition in excel: " + key.display());
            }
            if (rowIssues.isEmpty()) {
                validDefinitions.add(row);
            } else {
                issues.add(new ConsoleWorkflowExcelRowIssueResponse(DEF_SHEET, row.rowNo(), key.display(), row.workflowCode(), row.version(), List.copyOf(rowIssues)));
            }
        }

        Set<NodeKey> nodeKeys = new LinkedHashSet<>();
        for (WorkflowNodeRow row : session.nodes()) {
            List<String> rowIssues = new ArrayList<>();
            WorkflowKey workflowKey = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion());
            if (!definitionKeys.contains(workflowKey)) {
                rowIssues.add("workflow node references missing definition: " + workflowKey.display());
            }
            if (!hasText(row.tenantId()) || !hasText(row.workflowCode()) || row.workflowVersion() == null || !hasText(row.nodeCode()) || !hasText(row.nodeName()) || !hasText(row.nodeType())) {
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
                validNodes.add(row);
            } else {
                issues.add(new ConsoleWorkflowExcelRowIssueResponse(NODE_SHEET, row.rowNo(), nodeKey.display(), row.workflowCode(), row.workflowVersion(), List.copyOf(rowIssues)));
            }
        }

        Set<EdgeKey> edgeKeys = new LinkedHashSet<>();
        for (WorkflowEdgeRow row : session.edges()) {
            List<String> rowIssues = new ArrayList<>();
            WorkflowKey workflowKey = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion());
            if (!definitionKeys.contains(workflowKey)) {
                rowIssues.add("workflow edge references missing definition: " + workflowKey.display());
            }
            if (!hasText(row.tenantId()) || !hasText(row.workflowCode()) || row.workflowVersion() == null || !hasText(row.fromNodeCode()) || !hasText(row.toNodeCode()) || !hasText(row.edgeType())) {
                rowIssues.add("workflow edge fields are incomplete");
            }
            if (hasText(row.edgeType()) && !EDGE_TYPES.contains(row.edgeType())) {
                rowIssues.add("edge_type must be one of " + EDGE_TYPES);
            }
            EdgeKey edgeKey = EdgeKey.of(workflowKey, row.fromNodeCode(), row.toNodeCode(), row.edgeType());
            if (!edgeKeys.add(edgeKey)) {
                rowIssues.add("duplicate workflow edge in excel: " + edgeKey.display());
            }
            if (rowIssues.isEmpty()) {
                validEdges.add(row);
            } else {
                issues.add(new ConsoleWorkflowExcelRowIssueResponse(EDGE_SHEET, row.rowNo(), edgeKey.display(), row.workflowCode(), row.workflowVersion(), List.copyOf(rowIssues)));
            }
        }

        int totalRows = session.definitions().size() + session.nodes().size() + session.edges().size();
        int validRows = validDefinitions.size() + validNodes.size() + validEdges.size();
        return ValidationResult.builder()
                .counts(ValidationCounts.builder()
                        .definitionRows(session.definitions().size())
                        .nodeRows(session.nodes().size())
                        .edgeRows(session.edges().size())
                        .totalRows(totalRows)
                        .validRows(validRows)
                        .invalidRows(totalRows - validRows)
                        .build())
                .data(ValidationData.builder()
                        .definitions(validDefinitions)
                        .nodes(validNodes)
                        .edges(validEdges)
                        .issues(issues)
                        .build())
                .build();
    }

    private byte[] writeWorkbook(String tenantId, List<WorkflowDefinitionEntity> definitions) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(50); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet definitionSheet = workbook.createSheet(DEF_SHEET);
            Sheet nodeSheet = workbook.createSheet(NODE_SHEET);
            Sheet edgeSheet = workbook.createSheet(EDGE_SHEET);
            definitionSheet.createFreezePane(0, 1);
            nodeSheet.createFreezePane(0, 1);
            edgeSheet.createFreezePane(0, 1);
            CellStyle headerStyle = createHeaderStyle(workbook);
            writeHeaders(definitionSheet, DEF_COLUMNS, headerStyle);
            writeHeaders(nodeSheet, NODE_COLUMNS, headerStyle);
            writeHeaders(edgeSheet, EDGE_COLUMNS, headerStyle);

            int defRowIndex = 1;
            for (WorkflowDefinitionEntity definition : definitions) {
                Row row = definitionSheet.createRow(defRowIndex++);
                writeCell(row, 0, definition.getTenantId());
                writeCell(row, 1, definition.getWorkflowCode());
                writeCell(row, 2, definition.getWorkflowName());
                writeCell(row, 3, definition.getWorkflowType());
                writeCell(row, 4, definition.getVersion());
                writeCell(row, 5, definition.getEnabled());
                writeCell(row, 6, definition.getDescription());
            }

            int nodeRowIndex = 1;
            int edgeRowIndex = 1;
            for (WorkflowDefinitionEntity definition : definitions) {
                List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(new WorkflowNodeQuery(
                        tenantId,
                        definition.getId(),
                        definition.getWorkflowCode(),
                        null,
                        null,
                        null,
                        null
                ));
                for (WorkflowNodeEntity node : nodes) {
                    Row row = nodeSheet.createRow(nodeRowIndex++);
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
                List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectByQuery(new WorkflowEdgeQuery(
                        tenantId,
                        definition.getId(),
                        definition.getWorkflowCode(),
                        null,
                        null,
                        null,
                        null,
                        null
                ));
                for (WorkflowEdgeEntity edge : edges) {
                    Row row = edgeSheet.createRow(edgeRowIndex++);
                    writeCell(row, 0, tenantId);
                    writeCell(row, 1, definition.getWorkflowCode());
                    writeCell(row, 2, definition.getVersion());
                    writeCell(row, 3, edge.getFromNodeCode());
                    writeCell(row, 4, edge.getToNodeCode());
                    writeCell(row, 5, edge.getEdgeType());
                    writeCell(row, 6, edge.getConditionExpr());
                    writeCell(row, 7, edge.getEnabled());
                }
            }

            applyValidations(definitionSheet, nodeSheet, edgeSheet);
            setWidths(definitionSheet, DEF_COLUMNS);
            setWidths(nodeSheet, NODE_COLUMNS);
            setWidths(edgeSheet, EDGE_COLUMNS);
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

    private void applyValidations(Sheet definitionSheet, Sheet nodeSheet, Sheet edgeSheet) {
        addListValidation(definitionSheet, 3, WORKFLOW_TYPES.toArray(String[]::new));
        addBooleanValidation(definitionSheet, 5);
        addListValidation(nodeSheet, 5, NODE_TYPES.toArray(String[]::new));
        addListValidation(nodeSheet, 11, RETRY_POLICIES.toArray(String[]::new));
        addBooleanValidation(nodeSheet, 15);
        addListValidation(edgeSheet, 5, EDGE_TYPES.toArray(String[]::new));
        addBooleanValidation(edgeSheet, 7);
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

    private void setWidths(Sheet sheet, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            sheet.setColumnWidth(i, Math.min(12000, Math.max(18, columns.get(i).length() + 4) * 256));
        }
    }

    private void createReadmeSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("README");
        sheet.setColumnWidth(0, 16000);
        String[] lines = {
                "workflow definition / node / edge 维护模板",
                "1. 主数据页必须在第一个 sheet，并按 definition / node / edge 顺序导出。",
                "2. 导出结果可直接修改后再导入。",
                "3. workflow_code + version 是跨 sheet 关联键。",
                "4. node_params 请保持合法 JSON。",
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
                {"workflow_type", "DAG", "dag workflow"},
                {"workflow_type", "PIPELINE", "pipeline workflow"},
                {"workflow_type", "MIXED", "mixed workflow"},
                {"node_type", "TASK", "task node"},
                {"node_type", "GATEWAY", "gateway node"},
                {"node_type", "FILE_STEP", "file step node"},
                {"node_type", "START", "start node"},
                {"node_type", "END", "end node"},
                {"node_type", "JOB", "job node"},
                {"retry_policy", "NONE", "no retry"},
                {"retry_policy", "FIXED", "fixed retry"},
                {"retry_policy", "EXPONENTIAL", "exponential retry"},
                {"edge_type", "SUCCESS", "success edge"},
                {"edge_type", "FAILURE", "failure edge"},
                {"edge_type", "CONDITION", "condition edge"},
                {"edge_type", "ALWAYS", "always edge"},
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
        header.createCell(3).setCellValue("workflow_code");
        header.createCell(4).setCellValue("workflow_version");
        header.createCell(5).setCellValue("error_reason");
        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 32 * 256);
        sheet.setColumnWidth(3, 24 * 256);
        sheet.setColumnWidth(4, 18 * 256);
        sheet.setColumnWidth(5, 50 * 256);
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

    private record DefinitionChangeContext(WorkflowDefinitionRow row, int nodeCount, int edgeCount,
                                            String reason, String operatorId, String traceId, String action) {}

    private void logDefinitionChange(DefinitionChangeContext ctx) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", ctx.row().tenantId(),
                "configType", "WORKFLOW_DEFINITION",
                "configKey", ctx.row().workflowCode() + "#" + ctx.row().version(),
                "versionNo", ctx.row().version(),
                "changeAction", ctx.action(),
                "changeResult", "SUCCESS",
                "operatorType", "USER",
                "operatorId", ConsoleTextSanitizer.safeInput(ctx.operatorId(), 64),
                "traceId", ConsoleTextSanitizer.safeInput(ctx.traceId(), 128),
                "changeSummaryJson", JsonUtils.toJson(mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(ctx.reason(), 512),
                        "detail", mapOf(
                                "workflowName", ctx.row().workflowName(),
                                "workflowType", ctx.row().workflowType(),
                                "enabled", ctx.row().enabled(),
                                "nodeCount", ctx.nodeCount(),
                                "edgeCount", ctx.edgeCount()
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
                row.description()
        );
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
                row.enabled()
        );
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
                row.enabled()
        );
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

    private void validateHeaders(String sheetName, Map<String, Integer> headerIndex, Set<String> requiredHeaders) {
        Set<String> missing = new LinkedHashSet<>(requiredHeaders);
        missing.removeAll(headerIndex.keySet());
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "excel header missing for sheet " + sheetName + ": " + String.join(", ", missing));
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
        if (List.of("TRUE", "Y", "1", "YES").contains(upper)) {
            return true;
        }
        if (List.of("FALSE", "N", "0", "NO").contains(upper)) {
            return false;
        }
        return defaultValue;
    }

    private ParsedWorkbook parseWorkbook(byte[] bytes, String tenantId, String originalFileName) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, "excel workbook has no sheet");
            }
            List<WorkflowDefinitionRow> definitions = parseDefinitionSheet(findSheet(workbook, DEF_SHEET), tenantId);
            List<WorkflowNodeRow> nodes = parseNodeSheet(findSheet(workbook, NODE_SHEET), tenantId);
            List<WorkflowEdgeRow> edges = parseEdgeSheet(findSheet(workbook, EDGE_SHEET), tenantId);
            return new ParsedWorkbook(fileNameOrDefault(originalFileName), tenantId, definitions, nodes, edges);
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "failed to read excel workbook: " + exception.getMessage());
        }
    }

    private Map<WorkflowKey, List<WorkflowNodeRow>> groupNodes(List<WorkflowNodeRow> rows) {
        Map<WorkflowKey, List<WorkflowNodeRow>> grouped = new LinkedHashMap<>();
        for (WorkflowNodeRow row : rows) {
            grouped.computeIfAbsent(WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion()), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private Map<WorkflowKey, List<WorkflowEdgeRow>> groupEdges(List<WorkflowEdgeRow> rows) {
        Map<WorkflowKey, List<WorkflowEdgeRow>> grouped = new LinkedHashMap<>();
        for (WorkflowEdgeRow row : rows) {
            grouped.computeIfAbsent(WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion()), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private record ParsedWorkbook(String fileName, String tenantId, List<WorkflowDefinitionRow> definitions, List<WorkflowNodeRow> nodes, List<WorkflowEdgeRow> edges) {
    }

    private record ParsedSession(String fileName, String tenantId, Instant uploadedAt, List<WorkflowDefinitionRow> definitions, List<WorkflowNodeRow> nodes, List<WorkflowEdgeRow> edges) {
    }

    @Builder
    private record ValidationResult(ValidationCounts counts,
                                    ValidationData data) {
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
    private record ValidationCounts(int definitionRows,
                                    int nodeRows,
                                    int edgeRows,
                                    int totalRows,
                                    int validRows,
                                    int invalidRows) {
    }

    @Builder
    private record ValidationData(List<WorkflowDefinitionRow> definitions,
                                  List<WorkflowNodeRow> nodes,
                                  List<WorkflowEdgeRow> edges,
                                  List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    }

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

    private record EdgeKey(WorkflowKey workflowKey, String fromNodeCode, String toNodeCode, String edgeType) {
        static EdgeKey of(WorkflowKey workflowKey, String fromNodeCode, String toNodeCode, String edgeType) {
            return new EdgeKey(workflowKey, fromNodeCode, toNodeCode, edgeType);
        }

        String display() {
            return workflowKey.display() + "/" + fromNodeCode + "->" + toNodeCode + "(" + edgeType + ")";
        }
    }

    private record SheetRow(int rowNo, Map<String, String> values) {
    }
}
