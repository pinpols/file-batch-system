package io.github.pinpols.batch.console.domain.workflow.infrastructure.query;

import static io.github.pinpols.batch.console.shared.query.ConsoleQuerySupport.*;

import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowDefinitionQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowEdgeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowNodeQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowNodeRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowRunQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.query.WorkflowTopologyQueryRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowDefinitionResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowEdgeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowRunResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowTopologyResponse;
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
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作流相关查询子服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleWorkflowQueryService {

  private final TenantIdResolver tenantGuard;
  private final ConsoleWorkflowQueryMappers workflowMappers;
  private final LocalizedErrorRenderer localizedErrorRenderer;

  public PageResponse<ConsoleWorkflowDefinitionResponse> workflowDefinitions(
      WorkflowDefinitionQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    WorkflowDefinitionQuery query = new WorkflowDefinitionQuery(
        tenantId,
        request.getWorkflowCode(),
        request.getWorkflowName(),
        request.getWorkflowType(),
        request.getRelatedJobCode(),
        request.getSingleJobWorkflow(),
        request.getVersion(),
        request.getEnabled(),
        pageRequest);
    List<WorkflowDefinitionEntity> rows =
        workflowMappers.workflowDefinitionMapper.selectByQuery(query);
    long total = workflowMappers.workflowDefinitionMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowDefinitionResponse);
  }

  public PageResponse<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    String requiredTenantId = TenantScope.requireTenant(tenantId);
    WorkflowNodeQuery query = new WorkflowNodeQuery(
        requiredTenantId,
        request.getWorkflowDefinitionId(),
        request.getWorkflowCode(),
        request.getNodeCode(),
        request.getNodeType(),
        request.getEnabled(),
        pageRequest);
    List<WorkflowNodeEntity> rows = workflowMappers.workflowNodeMapper.selectByQuery(query);
    long total = workflowMappers.workflowNodeMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowNodeResponse);
  }

  public PageResponse<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    String requiredTenantId = TenantScope.requireTenant(tenantId);
    WorkflowEdgeQuery query = new WorkflowEdgeQuery(
        requiredTenantId,
        request.getWorkflowDefinitionId(),
        request.getWorkflowCode(),
        request.getFromNodeCode(),
        request.getToNodeCode(),
        request.getEdgeType(),
        request.getEnabled(),
        pageRequest);
    List<WorkflowEdgeEntity> rows = workflowMappers.workflowEdgeMapper.selectByQuery(query);
    long total = workflowMappers.workflowEdgeMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowEdgeResponse);
  }

  public PageResponse<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    Long decodedCursorId = decodeCursorId(request.getCursor());
    WorkflowRunQuery query = new WorkflowRunQuery(
        tenantId,
        request.getWorkflowDefinitionId(),
        request.getRelatedJobInstanceId(),
        request.getRunStatus(),
        request.getCurrentNodeCode(),
        request.getTraceId(),
        pageRequest,
        decodedCursorId);
    List<WorkflowRunEntity> rows = workflowMappers.workflowRunMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toWorkflowRunResponse, WorkflowRunEntity::getId);
    }
    long total = workflowMappers.workflowRunMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowRunResponse);
  }

  public ConsoleWorkflowRunResponse workflowRun(String tenantId, Long id) {
    String resolvedTenantId = resolveTenant(tenantGuard, tenantId);
    WorkflowRunEntity entity = workflowMappers.workflowRunMapper.selectById(resolvedTenantId, id);
    WorkflowRunEntity existingRun = requireNotNull(entity, "workflow run not found");
    return toWorkflowRunResponse(existingRun);
  }

  public PageResponse<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(
      WorkflowNodeRunQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    Long decodedCursorId = decodeCursorId(request.getCursor());
    WorkflowNodeRunQuery query = new WorkflowNodeRunQuery(
        tenantId,
        request.getWorkflowRunId(),
        request.getNodeCode(),
        request.getNodeStatus(),
        request.getTraceId(),
        pageRequest,
        decodedCursorId,
        cursorMode);
    List<WorkflowNodeRunEntity> rows = workflowMappers.workflowNodeRunMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(
          pageRequest, rows, this::toWorkflowNodeRunResponse, WorkflowNodeRunEntity::getId);
    }
    long total = workflowMappers.workflowNodeRunMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toWorkflowNodeRunResponse);
  }

  public ConsoleWorkflowNodeRunResponse workflowNodeRun(String tenantId, Long id) {
    String resolvedTenantId = resolveTenant(tenantGuard, tenantId);
    WorkflowNodeRunEntity entity =
        workflowMappers.workflowNodeRunMapper.selectById(resolvedTenantId, id);
    WorkflowNodeRunEntity existingNodeRun = requireNotNull(entity, "workflow node run not found");
    return toWorkflowNodeRunResponse(existingNodeRun);
  }

  public ConsoleWorkflowTopologyResponse workflowTopology(WorkflowTopologyQueryRequest request) {
    WorkflowDefinitionQuery.WorkflowDefinitionQueryBuilder definitionQueryBuilder =
        WorkflowDefinitionQuery.builder();
    String definitionTenantId = resolveTenant(tenantGuard, request.getTenantId());
    WorkflowDefinitionQuery definitionQuery = definitionQueryBuilder
        .tenantId(definitionTenantId)
        .workflowCode(request.getWorkflowCode())
        .version(request.getVersion())
        .enabled(true)
        .build();
    List<WorkflowDefinitionEntity> definitions =
        workflowMappers.workflowDefinitionMapper.selectByQuery(definitionQuery);
    WorkflowDefinitionEntity selectedDefinition = definitions.isEmpty() ? null : definitions.get(0);
    if (selectedDefinition == null) {
      return new ConsoleWorkflowTopologyResponse(null, List.of(), List.of(), List.of(), List.of());
    }
    String nodeResolvedTenantId = resolveTenant(tenantGuard, request.getTenantId());
    String nodeTenantId = TenantScope.requireTenant(nodeResolvedTenantId);
    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.builder()
        .tenantId(nodeTenantId)
        .workflowDefinitionId(selectedDefinition.getId())
        .enabled(true)
        .build();
    List<WorkflowNodeEntity> nodes = workflowMappers.workflowNodeMapper.selectByQuery(nodeQuery);
    String edgeResolvedTenantId = resolveTenant(tenantGuard, request.getTenantId());
    String edgeTenantId = TenantScope.requireTenant(edgeResolvedTenantId);
    WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.builder()
        .tenantId(edgeTenantId)
        .workflowDefinitionId(selectedDefinition.getId())
        .enabled(true)
        .build();
    List<WorkflowEdgeEntity> edges = workflowMappers.workflowEdgeMapper.selectByQuery(edgeQuery);
    List<WorkflowRunEntity> workflowRuns = List.of();
    List<WorkflowNodeRunEntity> nodeRuns = List.of();
    if (request.getWorkflowRunId() != null) {
      String runTenantId = resolveTenant(tenantGuard, request.getTenantId());
      WorkflowRunQuery workflowRunQuery =
          WorkflowRunQuery.ofDefinition(runTenantId, selectedDefinition.getId());
      List<WorkflowRunEntity> definitionRuns =
          workflowMappers.workflowRunMapper.selectByQuery(workflowRunQuery);
      WorkflowRunEntity run = definitionRuns.stream()
          .filter(item -> request.getWorkflowRunId().equals(item.getId()))
          .findFirst()
          .orElse(null);
      if (run != null) {
        workflowRuns = List.of(run);
        String nodeRunTenantId = resolveTenant(tenantGuard, request.getTenantId());
        WorkflowNodeRunQuery nodeRunQuery =
            WorkflowNodeRunQuery.ofWorkflowRun(nodeRunTenantId, request.getWorkflowRunId());
        nodeRuns = workflowMappers.workflowNodeRunMapper.selectByQuery(nodeRunQuery);
      }
    }
    ConsoleWorkflowDefinitionResponse definitionResponse =
        toWorkflowDefinitionResponse(selectedDefinition);
    List<ConsoleWorkflowNodeResponse> nodeResponses =
        nodes.stream().map(this::toWorkflowNodeResponse).toList();
    List<ConsoleWorkflowEdgeResponse> edgeResponses =
        edges.stream().map(this::toWorkflowEdgeResponse).toList();
    List<ConsoleWorkflowRunResponse> workflowRunResponses =
        workflowRuns.stream().map(this::toWorkflowRunResponse).toList();
    List<ConsoleWorkflowNodeRunResponse> nodeRunResponses =
        nodeRuns.stream().map(this::toWorkflowNodeRunResponse).toList();
    return new ConsoleWorkflowTopologyResponse(
        definitionResponse, nodeResponses, edgeResponses, workflowRunResponses, nodeRunResponses);
  }

  private ConsoleWorkflowDefinitionResponse toWorkflowDefinitionResponse(
      WorkflowDefinitionEntity entity) {
    ConsoleWorkflowDefinitionResponse response = ConsoleWorkflowDefinitionResponse.builder()
        .id(entity.getId())
        .tenantId(display(entity.getTenantId()))
        .workflowCode(display(entity.getWorkflowCode()))
        .workflowName(display(entity.getWorkflowName()))
        .workflowType(display(entity.getWorkflowType()))
        .scheduleType(display(entity.getScheduleType()))
        .scheduleExpr(display(entity.getScheduleExpr()))
        .timezone(display(entity.getTimezone()))
        .triggerMode(display(entity.getTriggerMode()))
        .calendarCode(display(entity.getCalendarCode()))
        .dependsOnJobCode(display(entity.getDependsOnJobCode()))
        .version(entity.getVersion())
        .enabled(entity.getEnabled())
        .scheduleEnabled(entity.getScheduleEnabled())
        .description(display(entity.getDescription()))
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .singleJobDefinitionId(entity.getSingleJobDefinitionId())
        .build();
    return response;
  }

  private ConsoleWorkflowNodeResponse toWorkflowNodeResponse(WorkflowNodeEntity entity) {
    Long id = entity.getId();
    Long workflowDefinitionId = entity.getWorkflowDefinitionId();
    String nodeCode = display(entity.getNodeCode());
    String nodeName = display(entity.getNodeName());
    String nodeType = display(entity.getNodeType());
    String relatedJobCode = display(entity.getRelatedJobCode());
    String workerGroup = display(entity.getWorkerGroup());
    String windowCode = display(entity.getWindowCode());
    Integer nodeOrder = entity.getNodeOrder();
    String retryPolicy = display(entity.getRetryPolicy());
    Integer retryMaxCount = entity.getRetryMaxCount();
    Integer timeoutSeconds = entity.getTimeoutSeconds();
    String maskedNodeParams = SecretMasking.maskSensitiveJson(entity.getNodeParams());
    Boolean enabled = entity.getEnabled();
    Instant createdAt = entity.getCreatedAt();
    Instant updatedAt = entity.getUpdatedAt();
    String crossDayDependencies = display(entity.getCrossDayDependencies());
    Integer crossDayDependencyTimeoutSeconds = entity.getCrossDayDependencyTimeoutSeconds();
    return ConsoleWorkflowNodeResponse.builder()
        .id(id)
        .workflowDefinitionId(workflowDefinitionId)
        .nodeCode(nodeCode)
        .nodeName(nodeName)
        .nodeType(nodeType)
        .relatedJobCode(relatedJobCode)
        .workerGroup(workerGroup)
        .windowCode(windowCode)
        .nodeOrder(nodeOrder)
        .retryPolicy(retryPolicy)
        .retryMaxCount(retryMaxCount)
        .timeoutSeconds(timeoutSeconds)
        .nodeParams(maskedNodeParams)
        .enabled(enabled)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .crossDayDependencies(crossDayDependencies)
        .crossDayDependencyTimeoutSeconds(crossDayDependencyTimeoutSeconds)
        .build();
  }

  private ConsoleWorkflowEdgeResponse toWorkflowEdgeResponse(WorkflowEdgeEntity entity) {
    Long id = entity.getId();
    Long workflowDefinitionId = entity.getWorkflowDefinitionId();
    String fromNodeCode = display(entity.getFromNodeCode());
    String toNodeCode = display(entity.getToNodeCode());
    String edgeType = display(entity.getEdgeType());
    String conditionExpr = display(entity.getConditionExpr());
    Boolean enabled = entity.getEnabled();
    Instant createdAt = entity.getCreatedAt();
    Instant updatedAt = entity.getUpdatedAt();
    return new ConsoleWorkflowEdgeResponse(
        id,
        workflowDefinitionId,
        fromNodeCode,
        toNodeCode,
        edgeType,
        conditionExpr,
        enabled,
        createdAt,
        updatedAt);
  }

  private ConsoleWorkflowRunResponse toWorkflowRunResponse(WorkflowRunEntity entity) {
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    Long workflowDefinitionId = entity.getWorkflowDefinitionId();
    Long relatedJobInstanceId = entity.getRelatedJobInstanceId();
    LocalDate bizDate = entity.getBizDate();
    String runStatus = display(entity.getRunStatus());
    String currentNodeCode = display(entity.getCurrentNodeCode());
    String traceId = display(entity.getTraceId());
    Instant startedAt = entity.getStartedAt();
    Instant finishedAt = entity.getFinishedAt();
    Instant createdAt = entity.getCreatedAt();
    Instant updatedAt = entity.getUpdatedAt();
    return ConsoleWorkflowRunResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .workflowDefinitionId(workflowDefinitionId)
        .relatedJobInstanceId(relatedJobInstanceId)
        .bizDate(bizDate)
        .runStatus(runStatus)
        .currentNodeCode(currentNodeCode)
        .traceId(traceId)
        .startedAt(startedAt)
        .finishedAt(finishedAt)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }

  private ConsoleWorkflowNodeRunResponse toWorkflowNodeRunResponse(WorkflowNodeRunEntity entity) {
    // i18n 持久化:有 errorKey 时按当前 Locale 重渲染,否则透传 errorMessage(老 literal / 第三方异常)。
    String errorMessage = localizedErrorRenderer.render(entity);
    ConsoleWorkflowNodeRunResponse response = ConsoleWorkflowNodeRunResponse.builder()
        .id(entity.getId())
        .workflowRunId(entity.getWorkflowRunId())
        .jobInstanceId(entity.getJobInstanceId())
        .nodeCode(display(entity.getNodeCode()))
        .nodeType(display(entity.getNodeType()))
        .runSeq(entity.getRunSeq())
        .nodeStatus(display(entity.getNodeStatus()))
        .retryCount(entity.getRetryCount())
        .errorCode(display(entity.getErrorCode()))
        .errorMessage(display(errorMessage))
        .skipReasonCode(display(entity.getSkipReasonCode()))
        .skipReason(display(entity.getSkipReason()))
        .startedAt(entity.getStartedAt())
        .finishedAt(entity.getFinishedAt())
        .durationMs(entity.getDurationMs())
        .build();
    return response;
  }
}
