package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.ConsoleQuerySupport.*;

import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.domain.query.WorkflowNodeRunQuery;
import com.example.batch.console.domain.query.WorkflowRunQuery;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ConsoleWorkflowQueryMappers;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.query.WorkflowEdgeQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.web.query.WorkflowRunQueryRequest;
import com.example.batch.console.web.query.WorkflowTopologyQueryRequest;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowTopologyResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工作流相关查询子服务。
 */
@Service
@RequiredArgsConstructor
class ConsoleWorkflowQueryService {

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleWorkflowQueryMappers workflowMappers;

    PageResponse<ConsoleWorkflowDefinitionResponse> workflowDefinitions(WorkflowDefinitionQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<WorkflowDefinitionEntity> rows = workflowMappers.workflowDefinitionMapper.selectByQuery(new WorkflowDefinitionQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowCode(),
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled(),
                pageRequest
        ));
        long total = workflowMappers.workflowDefinitionMapper.countByQuery(new WorkflowDefinitionQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowCode(),
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled(),
                pageRequest
        ));
        return page(pageRequest, total, rows, this::toWorkflowDefinitionResponse);
    }

    PageResponse<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<WorkflowNodeEntity> rows = workflowMappers.workflowNodeMapper.selectByQuery(new WorkflowNodeQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getNodeCode(),
                request.getNodeType(),
                request.getEnabled(),
                pageRequest
        ));
        long total = workflowMappers.workflowNodeMapper.countByQuery(new WorkflowNodeQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getNodeCode(),
                request.getNodeType(),
                request.getEnabled(),
                pageRequest
        ));
        return page(pageRequest, total, rows, this::toWorkflowNodeResponse);
    }

    PageResponse<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<WorkflowEdgeEntity> rows = workflowMappers.workflowEdgeMapper.selectByQuery(new WorkflowEdgeQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getFromNodeCode(),
                request.getToNodeCode(),
                request.getEdgeType(),
                request.getEnabled(),
                pageRequest
        ));
        long total = workflowMappers.workflowEdgeMapper.countByQuery(new WorkflowEdgeQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getFromNodeCode(),
                request.getToNodeCode(),
                request.getEdgeType(),
                request.getEnabled(),
                pageRequest
        ));
        return page(pageRequest, total, rows, this::toWorkflowEdgeResponse);
    }

    PageResponse<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        WorkflowRunQuery query = new WorkflowRunQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getRelatedJobInstanceId(),
                request.getRunStatus(),
                request.getCurrentNodeCode(),
                request.getTraceId(),
                pageRequest
        );
        List<WorkflowRunEntity> rows = workflowMappers.workflowRunMapper.selectByQuery(query);
        long total = workflowMappers.workflowRunMapper.countByQuery(query);
        return page(pageRequest, total, rows, this::toWorkflowRunResponse);
    }

    ConsoleWorkflowRunResponse workflowRun(String tenantId, Long id) {
        WorkflowRunEntity entity = workflowMappers.workflowRunMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
        return toWorkflowRunResponse(requireNotNull(entity, "workflow run not found"));
    }

    PageResponse<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(WorkflowNodeRunQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        WorkflowNodeRunQuery query = new WorkflowNodeRunQuery(
                request.getWorkflowRunId(),
                request.getNodeCode(),
                request.getNodeStatus(),
                pageRequest
        );
        List<WorkflowNodeRunEntity> rows = workflowMappers.workflowNodeRunMapper.selectByQuery(query);
        long total = workflowMappers.workflowNodeRunMapper.countByQuery(query);
        return page(pageRequest, total, rows, this::toWorkflowNodeRunResponse);
    }

    ConsoleWorkflowNodeRunResponse workflowNodeRun(String tenantId, Long id) {
        WorkflowNodeRunEntity entity = workflowMappers.workflowNodeRunMapper.selectById(resolveTenant(tenantGuard, tenantId), id);
        return toWorkflowNodeRunResponse(requireNotNull(entity, "workflow node run not found"));
    }

    ConsoleWorkflowTopologyResponse workflowTopology(WorkflowTopologyQueryRequest request) {
        WorkflowDefinitionQuery definitionQuery = new WorkflowDefinitionQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getWorkflowCode(),
                null,
                null,
                request.getVersion(),
                true,
                null
        );
        List<WorkflowDefinitionEntity> definitions = workflowMappers.workflowDefinitionMapper.selectByQuery(definitionQuery);
        WorkflowDefinitionEntity selectedDefinition = definitions.isEmpty() ? null : definitions.get(0);
        if (selectedDefinition == null) {
            return new ConsoleWorkflowTopologyResponse(null, List.of(), List.of(), List.of(), List.of());
        }
        List<WorkflowNodeEntity> nodes = workflowMappers.workflowNodeMapper.selectByQuery(new WorkflowNodeQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                selectedDefinition.getId(),
                null,
                null,
                null,
                true,
                null
        ));
        List<WorkflowEdgeEntity> edges = workflowMappers.workflowEdgeMapper.selectByQuery(new WorkflowEdgeQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                selectedDefinition.getId(),
                null,
                null,
                null,
                null,
                true,
                null
        ));
        List<WorkflowRunEntity> workflowRuns = List.of();
        List<WorkflowNodeRunEntity> nodeRuns = List.of();
        if (request.getWorkflowRunId() != null) {
            WorkflowRunEntity run = workflowMappers.workflowRunMapper.selectByQuery(new WorkflowRunQuery(
                    resolveTenant(tenantGuard, request.getTenantId()),
                    selectedDefinition.getId(),
                    null,
                    null,
                    null,
                    null,
                    null
            )).stream()
                    .filter(item -> request.getWorkflowRunId().equals(item.getId()))
                    .findFirst()
                    .orElse(null);
            if (run != null) {
                workflowRuns = List.of(run);
                nodeRuns = workflowMappers.workflowNodeRunMapper.selectByQuery(new WorkflowNodeRunQuery(
                        request.getWorkflowRunId(),
                        null,
                        null,
                        null
                ));
            }
        }
        return new ConsoleWorkflowTopologyResponse(
                toWorkflowDefinitionResponse(selectedDefinition),
                nodes.stream().map(this::toWorkflowNodeResponse).toList(),
                edges.stream().map(this::toWorkflowEdgeResponse).toList(),
                workflowRuns.stream().map(this::toWorkflowRunResponse).toList(),
                nodeRuns.stream().map(this::toWorkflowNodeRunResponse).toList()
        );
    }

    private ConsoleWorkflowDefinitionResponse toWorkflowDefinitionResponse(WorkflowDefinitionEntity entity) {
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
                entity.getUpdatedAt()
        );
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
                entity.getUpdatedAt()
        );
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
                entity.getUpdatedAt()
        );
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
                entity.getUpdatedAt()
        );
    }

    private ConsoleWorkflowNodeRunResponse toWorkflowNodeRunResponse(WorkflowNodeRunEntity entity) {
        return new ConsoleWorkflowNodeRunResponse(
                entity.getId(),
                entity.getWorkflowRunId(),
                display(entity.getNodeCode()),
                display(entity.getNodeType()),
                entity.getRunSeq(),
                display(entity.getNodeStatus()),
                entity.getRetryCount(),
                display(entity.getErrorCode()),
                display(entity.getErrorMessage()),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getDurationMs()
        );
    }
}
