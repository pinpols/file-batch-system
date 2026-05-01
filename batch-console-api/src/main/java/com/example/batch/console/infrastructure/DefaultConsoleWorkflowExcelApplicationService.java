package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.COL_ENABLED;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
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
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowExcelSession;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.request.WorkflowExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionExcelRowResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeExcelRowResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelApplyResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelPreviewResponse;
import com.example.batch.console.web.response.ConsoleWorkflowExcelUploadResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeExcelRowResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
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

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final WorkflowExcelImportStore importStore;
  private final WorkflowExcelWorkbookWriter workbookWriter;
  private final WorkflowExcelSheetParser sheetParser;
  private final WorkflowExcelRowValidator rowValidator;

  @Override
  public ResponseEntity<InputStreamResource> exportWorkflowExcel(
      WorkflowDefinitionQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    WorkflowDefinitionQuery exportQuery =
        WorkflowDefinitionQuery.builder()
            .tenantId(tenantId)
            .workflowCode(request.getWorkflowCode())
            .workflowName(request.getWorkflowName())
            .workflowType(request.getWorkflowType())
            .version(request.getVersion())
            .enabled(request.getEnabled())
            .build();
    List<WorkflowDefinitionEntity> definitions =
        workflowDefinitionMapper.selectByQuery(exportQuery);
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
    WorkflowExcelSheetParser.ParsedWorkbook workbook =
        sheetParser.parseWorkbook(file.getBytes(), tenantId, file.getOriginalFilename());
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
    WorkflowExcelParsedSession session = loadSession(uploadToken);
    WorkflowExcelValidationResult validationResult = rowValidator.validate(session);
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
    WorkflowExcelParsedSession session = loadSession(uploadToken);
    WorkflowExcelValidationResult validationResult = rowValidator.validate(session);
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
    WorkflowExcelParsedSession session = loadSession(uploadToken);
    WorkflowExcelValidationResult validationResult = rowValidator.validate(session);
    if (validationResult.invalidRows() > 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.excel.invalid_workflow_rows");
    }
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = metadata.operatorId();
    String traceId = metadata.traceId();

    Map<WorkflowExcelKeys.WorkflowKey, List<WorkflowNodeRow>> nodesByWorkflow =
        groupNodes(validationResult.nodes());
    Map<WorkflowExcelKeys.WorkflowKey, List<WorkflowEdgeRow>> edgesByWorkflow =
        groupEdges(validationResult.edges());

    ApplyCounters counters = new ApplyCounters();
    for (WorkflowDefinitionRow row : validationResult.definitions()) {
      WorkflowExcelKeys.WorkflowKey key =
          WorkflowExcelKeys.WorkflowKey.of(row.tenantId(), row.workflowCode(), row.version());
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
      DefinitionChangeContext changeCtx =
          DefinitionChangeContext.builder()
              .row(row)
              .nodeCount(workflowNodes.size())
              .edgeCount(workflowEdges.size())
              .reason(request.getReason())
              .operatorId(operatorId)
              .traceId(traceId)
              .action(existing == null ? "CREATE" : "PUBLISH")
              .build();
      logDefinitionChange(changeCtx);
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

  private WorkflowExcelParsedSession loadSession(String uploadToken) {
    WorkflowExcelSession session =
        Guard.requireFound(importStore.get(uploadToken), "excel upload session not found");
    tenantGuard.assertTenantAllowed(session.tenantId());
    return new WorkflowExcelParsedSession(
        session.fileName(),
        session.tenantId(),
        session.uploadedAt(),
        session.definitions(),
        session.nodes(),
        session.edges());
  }

  @Builder
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

  private Map<WorkflowExcelKeys.WorkflowKey, List<WorkflowNodeRow>> groupNodes(
      List<WorkflowNodeRow> rows) {
    Map<WorkflowExcelKeys.WorkflowKey, List<WorkflowNodeRow>> grouped = new LinkedHashMap<>();
    for (WorkflowNodeRow row : rows) {
      grouped
          .computeIfAbsent(
              WorkflowExcelKeys.WorkflowKey.of(
                  row.tenantId(), row.workflowCode(), row.workflowVersion()),
              key -> new ArrayList<>())
          .add(row);
    }
    return grouped;
  }

  private Map<WorkflowExcelKeys.WorkflowKey, List<WorkflowEdgeRow>> groupEdges(
      List<WorkflowEdgeRow> rows) {
    Map<WorkflowExcelKeys.WorkflowKey, List<WorkflowEdgeRow>> grouped = new LinkedHashMap<>();
    for (WorkflowEdgeRow row : rows) {
      grouped
          .computeIfAbsent(
              WorkflowExcelKeys.WorkflowKey.of(
                  row.tenantId(), row.workflowCode(), row.workflowVersion()),
              key -> new ArrayList<>())
          .add(row);
    }
    return grouped;
  }
}
