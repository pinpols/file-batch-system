package io.github.pinpols.batch.console.domain.workflow.infrastructure.query;

import static io.github.pinpols.batch.console.domain.observability.infrastructure.ConsoleQuerySupport.*;

import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.rbac.support.TenantScope;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeRunQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowRunQuery;
import io.github.pinpols.batch.console.domain.workflow.support.ConsoleWorkflowQueryMappers;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowEdgeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowNodeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowNodeRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.query.WorkflowTopologyQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowDefinitionResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowRunResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.ConsoleWorkflowTopologyResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作流相关查询子服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleWorkflowQueryService {

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleWorkflowQueryMappers workflowMappers;
  private final LocalizedErrorRenderer localizedErrorRenderer;

  public PageResponse<ConsoleWorkflowDefinitionResponse> workflowDefinitions(
      WorkflowDefinitionQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<WorkflowDefinitionEntity> rows =
        workflowMappers.workflowDefinitionMapper.selectByQuery(
            new WorkflowDefinitionQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowCode(),
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled(),
                pageRequest));
    long total =
        workflowMappers.workflowDefinitionMapper.countByQuery(
            new WorkflowDefinitionQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowCode(),
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled(),
                pageRequest));
    return page(pageRequest, total, rows, this::toWorkflowDefinitionResponse);
  }

  public PageResponse<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<WorkflowNodeEntity> rows =
        workflowMappers.workflowNodeMapper.selectByQuery(
            new WorkflowNodeQuery(
                TenantScope.requireTenant(resolveTenant(tenantGuard, request.getTenantId())),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getNodeCode(),
                request.getNodeType(),
                request.getEnabled(),
                pageRequest));
    long total =
        workflowMappers.workflowNodeMapper.countByQuery(
            new WorkflowNodeQuery(
                TenantScope.requireTenant(resolveTenant(tenantGuard, request.getTenantId())),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getNodeCode(),
                request.getNodeType(),
                request.getEnabled(),
                pageRequest));
    return page(pageRequest, total, rows, this::toWorkflowNodeResponse);
  }

  public PageResponse<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<WorkflowEdgeEntity> rows =
        workflowMappers.workflowEdgeMapper.selectByQuery(
            new WorkflowEdgeQuery(
                TenantScope.requireTenant(resolveTenant(tenantGuard, request.getTenantId())),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getFromNodeCode(),
                request.getToNodeCode(),
                request.getEdgeType(),
                request.getEnabled(),
                pageRequest));
    long total =
        workflowMappers.workflowEdgeMapper.countByQuery(
            new WorkflowEdgeQuery(
                TenantScope.requireTenant(resolveTenant(tenantGuard, request.getTenantId())),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getFromNodeCode(),
                request.getToNodeCode(),
                request.getEdgeType(),
                request.getEnabled(),
                pageRequest));
    return page(pageRequest, total, rows, this::toWorkflowEdgeResponse);
  }

  public PageResponse<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request) {
    boolean cursorMode = request.getCursor() != null && !request.getCursor().isBlank();
    PageRequest pageRequest =
        cursorMode
            ? new PageRequest(1, request.getPageSize())
            : new PageRequest(request.getPageNo(), request.getPageSize());
    WorkflowRunQuery query =
        new WorkflowRunQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getWorkflowDefinitionId(),
            request.getRelatedJobInstanceId(),
            request.getRunStatus(),
            request.getCurrentNodeCode(),
            request.getTraceId(),
            pageRequest,
            decodeCursorId(request.getCursor()));
    List<WorkflowRunEntity> rows = workflowMappers.workflowRunMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toWorkflowRunResponse, WorkflowRunEntity::getId);
    }
    long total = workflowMappers.workflowRunMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowRunResponse);
  }

  public ConsoleWorkflowRunResponse workflowRun(String tenantId, Long id) {
    WorkflowRunEntity entity =
        workflowMappers.workflowRunMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
    return toWorkflowRunResponse(requireNotNull(entity, "workflow run not found"));
  }

  public PageResponse<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(
      WorkflowNodeRunQueryRequest request) {
    boolean cursorMode = request.getCursor() != null && !request.getCursor().isBlank();
    PageRequest pageRequest =
        cursorMode
            ? new PageRequest(1, request.getPageSize())
            : new PageRequest(request.getPageNo(), request.getPageSize());
    WorkflowNodeRunQuery query =
        new WorkflowNodeRunQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getWorkflowRunId(),
            request.getNodeCode(),
            request.getNodeStatus(),
            request.getTraceId(),
            pageRequest,
            decodeCursorId(request.getCursor()));
    List<WorkflowNodeRunEntity> rows = workflowMappers.workflowNodeRunMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toWorkflowNodeRunResponse, WorkflowNodeRunEntity::getId);
    }
    long total = workflowMappers.workflowNodeRunMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowNodeRunResponse);
  }

  public ConsoleWorkflowNodeRunResponse workflowNodeRun(String tenantId, Long id) {
    WorkflowNodeRunEntity entity =
        workflowMappers.workflowNodeRunMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
    return toWorkflowNodeRunResponse(requireNotNull(entity, "workflow node run not found"));
  }

  public ConsoleWorkflowTopologyResponse workflowTopology(WorkflowTopologyQueryRequest request) {
    WorkflowDefinitionQuery definitionQuery =
        new WorkflowDefinitionQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getWorkflowCode(),
            null,
            null,
            request.getVersion(),
            true,
            null);
    List<WorkflowDefinitionEntity> definitions =
        workflowMappers.workflowDefinitionMapper.selectByQuery(definitionQuery);
    WorkflowDefinitionEntity selectedDefinition = definitions.isEmpty() ? null : definitions.get(0);
    if (selectedDefinition == null) {
      return new ConsoleWorkflowTopologyResponse(null, List.of(), List.of(), List.of(), List.of());
    }
    List<WorkflowNodeEntity> nodes =
        workflowMappers.workflowNodeMapper.selectByQuery(
            new WorkflowNodeQuery(
                TenantScope.requireTenant(resolveTenant(tenantGuard, request.getTenantId())),
                selectedDefinition.getId(),
                null,
                null,
                null,
                true,
                null));
    List<WorkflowEdgeEntity> edges =
        workflowMappers.workflowEdgeMapper.selectByQuery(
            new WorkflowEdgeQuery(
                TenantScope.requireTenant(resolveTenant(tenantGuard, request.getTenantId())),
                selectedDefinition.getId(),
                null,
                null,
                null,
                null,
                true,
                null));
    List<WorkflowRunEntity> workflowRuns = List.of();
    List<WorkflowNodeRunEntity> nodeRuns = List.of();
    if (request.getWorkflowRunId() != null) {
      WorkflowRunEntity run =
          workflowMappers
              .workflowRunMapper
              .selectByQuery(
                  new WorkflowRunQuery(
                      resolveTenant(tenantGuard, request.getTenantId()),
                      selectedDefinition.getId(),
                      null,
                      null,
                      null,
                      null,
                      null,
                      null))
              .stream()
              .filter(item -> request.getWorkflowRunId().equals(item.getId()))
              .findFirst()
              .orElse(null);
      if (run != null) {
        workflowRuns = List.of(run);
        nodeRuns =
            workflowMappers.workflowNodeRunMapper.selectByQuery(
                new WorkflowNodeRunQuery(
                    resolveTenant(tenantGuard, request.getTenantId()),
                    request.getWorkflowRunId(),
                    null,
                    null,
                    null,
                    null,
                    null));
      }
    }
    return new ConsoleWorkflowTopologyResponse(
        toWorkflowDefinitionResponse(selectedDefinition),
        nodes.stream().map(this::toWorkflowNodeResponse).toList(),
        edges.stream().map(this::toWorkflowEdgeResponse).toList(),
        workflowRuns.stream().map(this::toWorkflowRunResponse).toList(),
        nodeRuns.stream().map(this::toWorkflowNodeRunResponse).toList());
  }

  private ConsoleWorkflowDefinitionResponse toWorkflowDefinitionResponse(
      WorkflowDefinitionEntity entity) {
    return new ConsoleWorkflowDefinitionResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getWorkflowCode()),
        display(entity.getWorkflowName()),
        display(entity.getWorkflowType()),
        entity.getVersion(),
        entity.getEnabled(),
        display(entity.getDescription()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleWorkflowNodeResponse toWorkflowNodeResponse(WorkflowNodeEntity entity) {
    return new ConsoleWorkflowNodeResponse(
        entity.getId(),
        entity.getWorkflowDefinitionId(),
        display(entity.getNodeCode()),
        display(entity.getNodeName()),
        display(entity.getNodeType()),
        display(entity.getRelatedJobCode()),
        display(entity.getRelatedPipelineCode()),
        display(entity.getWorkerGroup()),
        display(entity.getWindowCode()),
        entity.getNodeOrder(),
        display(entity.getRetryPolicy()),
        entity.getRetryMaxCount(),
        entity.getTimeoutSeconds(),
        display(entity.getNodeParams()),
        entity.getEnabled(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        display(entity.getCrossDayDependencies()),
        entity.getCrossDayDependencyTimeoutSeconds());
  }

  private ConsoleWorkflowEdgeResponse toWorkflowEdgeResponse(WorkflowEdgeEntity entity) {
    return new ConsoleWorkflowEdgeResponse(
        entity.getId(),
        entity.getWorkflowDefinitionId(),
        display(entity.getFromNodeCode()),
        display(entity.getToNodeCode()),
        display(entity.getEdgeType()),
        display(entity.getConditionExpr()),
        entity.getEnabled(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleWorkflowRunResponse toWorkflowRunResponse(WorkflowRunEntity entity) {
    return new ConsoleWorkflowRunResponse(
        entity.getId(),
        display(entity.getTenantId()),
        entity.getWorkflowDefinitionId(),
        entity.getRelatedJobInstanceId(),
        entity.getBizDate(),
        display(entity.getRunStatus()),
        display(entity.getCurrentNodeCode()),
        display(entity.getTraceId()),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private ConsoleWorkflowNodeRunResponse toWorkflowNodeRunResponse(WorkflowNodeRunEntity entity) {
    // i18n 持久化:有 errorKey 时按当前 Locale 重渲染,否则透传 errorMessage(老 literal / 第三方异常)。
    String errorMessage = localizedErrorRenderer.render(entity);
    return new ConsoleWorkflowNodeRunResponse(
        entity.getId(),
        entity.getWorkflowRunId(),
        display(entity.getNodeCode()),
        display(entity.getNodeType()),
        entity.getRunSeq(),
        display(entity.getNodeStatus()),
        entity.getRetryCount(),
        display(entity.getErrorCode()),
        display(errorMessage),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getDurationMs());
  }
}
