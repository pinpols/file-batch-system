package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.mapper.param.WorkflowNodeUpsertParam;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.web.response.WorkflowDefinitionDetailResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ConsoleWorkflowDefinitionApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowDefinitionApplicationService implements ConsoleWorkflowDefinitionApplicationService {

    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final ConsoleTenantGuard tenantGuard;

    @Override
    public WorkflowDefinitionDetailResponse getById(Long id, String tenantId) {
        String resolvedTenant = tenantGuard.resolveTenant(tenantId);
        WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
        if (def == null) {
            throw new BizException(ResultCode.NOT_FOUND, "Workflow definition not found: " + id);
        }
        List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(
                new WorkflowNodeQuery(resolvedTenant, def.getId(), null, null, null, null, null));
        List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(
                new WorkflowEdgeQuery(resolvedTenant, def.getId(), null, null, null, null, null, null));
        return toDetailResponse(def, nodes, edges);
    }

    @Override
    @Transactional
    public WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request) {
        String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());

        WorkflowDefinitionEntity existing = definitionMapper.selectByUniqueKey(
                resolvedTenant, request.getWorkflowCode(), 1);
        if (existing != null) {
            throw new BizException(ResultCode.CONFLICT,
                    "Workflow definition already exists: " + request.getWorkflowCode());
        }

        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setTenantId(resolvedTenant);
        entity.setWorkflowCode(request.getWorkflowCode());
        entity.setWorkflowName(request.getWorkflowName());
        entity.setWorkflowType(request.getWorkflowType());
        entity.setVersion(1);
        entity.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        definitionMapper.insert(entity);

        upsertNodesAndEdges(entity.getId(), request);

        return loadDetail(resolvedTenant, entity.getId());
    }

    @Override
    @Transactional
    public WorkflowDefinitionDetailResponse update(Long id, WorkflowDefinitionSaveRequest request) {
        String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());

        WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
        if (def == null) {
            throw new BizException(ResultCode.NOT_FOUND, "Workflow definition not found: " + id);
        }

        definitionMapper.updateWorkflowDefinition(
                resolvedTenant, id,
                request.getWorkflowName(),
                request.getWorkflowType(),
                request.getEnabled());

        nodeMapper.deleteByWorkflowDefinitionId(id);
        edgeMapper.deleteByWorkflowDefinitionId(id);
        upsertNodesAndEdges(id, request);

        return loadDetail(resolvedTenant, id);
    }

    @Override
    public void toggleEnabled(Long id, String tenantId, Boolean enabled) {
        String resolvedTenant = tenantGuard.resolveTenant(tenantId);
        int rows = definitionMapper.toggleEnabled(resolvedTenant, id, enabled);
        if (rows == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "Workflow definition not found: " + id);
        }
    }

    @Override
    @Transactional
    public void delete(Long id, String tenantId) {
        String resolvedTenant = tenantGuard.resolveTenant(tenantId);

        WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
        if (def == null) {
            throw new BizException(ResultCode.NOT_FOUND, "Workflow definition not found: " + id);
        }

        nodeMapper.deleteByWorkflowDefinitionId(id);
        edgeMapper.deleteByWorkflowDefinitionId(id);
        definitionMapper.deleteByTenantAndId(resolvedTenant, id);
    }

    @Override
    public DagValidationResult validate(Long id, String tenantId) {
        String resolvedTenant = tenantGuard.resolveTenant(tenantId);
        WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
        if (def == null) {
            throw new BizException(ResultCode.NOT_FOUND, "Workflow definition not found: " + id);
        }

        List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(
                new WorkflowNodeQuery(resolvedTenant, def.getId(), null, null, null, null, null));
        List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(
                new WorkflowEdgeQuery(resolvedTenant, def.getId(), null, null, null, null, null, null));

        List<String> errors = validateDag(nodes, edges);
        return new DagValidationResult(errors.isEmpty(), errors);
    }

    // ---- internal helpers ----

    private void upsertNodesAndEdges(Long definitionId, WorkflowDefinitionSaveRequest request) {
        if (request.getNodes() != null) {
            for (WorkflowDefinitionSaveRequest.NodeItem n : request.getNodes()) {
                WorkflowNodeUpsertParam param = new WorkflowNodeUpsertParam();
                param.setWorkflowDefinitionId(definitionId);
                param.setNodeCode(n.getNodeCode());
                param.setNodeName(n.getNodeName());
                param.setNodeType(n.getNodeType());
                param.setRelatedJobCode(n.getRelatedJobCode());
                param.setRelatedPipelineCode(n.getRelatedPipelineCode());
                param.setWorkerGroup(n.getWorkerGroup());
                param.setWindowCode(n.getWindowCode());
                param.setNodeOrder(n.getNodeOrder());
                param.setRetryPolicy(n.getRetryPolicy());
                param.setRetryMaxCount(n.getRetryMaxCount());
                param.setTimeoutSeconds(n.getTimeoutSeconds());
                param.setNodeParams(n.getNodeParams());
                param.setEnabled(n.getEnabled());
                nodeMapper.upsertWorkflowNode(param);
            }
        }
        if (request.getEdges() != null) {
            for (WorkflowDefinitionSaveRequest.EdgeItem e : request.getEdges()) {
                WorkflowEdgeUpsertParam param = new WorkflowEdgeUpsertParam();
                param.setWorkflowDefinitionId(definitionId);
                param.setFromNodeCode(e.getFromNodeCode());
                param.setToNodeCode(e.getToNodeCode());
                param.setEdgeType(e.getEdgeType());
                param.setConditionExpr(e.getConditionExpr());
                param.setEnabled(e.getEnabled());
                edgeMapper.upsertWorkflowEdge(param);
            }
        }
    }

    private WorkflowDefinitionDetailResponse loadDetail(String tenantId, Long id) {
        WorkflowDefinitionEntity def = definitionMapper.selectById(tenantId, id);
        List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(
                new WorkflowNodeQuery(tenantId, id, null, null, null, null, null));
        List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(
                new WorkflowEdgeQuery(tenantId, id, null, null, null, null, null, null));
        return toDetailResponse(def, nodes, edges);
    }

    private WorkflowDefinitionDetailResponse toDetailResponse(
            WorkflowDefinitionEntity def,
            List<WorkflowNodeEntity> nodes,
            List<WorkflowEdgeEntity> edges) {
        return new WorkflowDefinitionDetailResponse(
                def.getId(), def.getTenantId(), def.getWorkflowCode(), def.getWorkflowName(),
                def.getWorkflowType(), def.getVersion(), def.getEnabled(),
                def.getDescription(), def.getCreatedAt(), def.getUpdatedAt(),
                nodes.stream().map(this::toNodeResponse).toList(),
                edges.stream().map(this::toEdgeResponse).toList());
    }

    private ConsoleWorkflowNodeResponse toNodeResponse(WorkflowNodeEntity n) {
        return new ConsoleWorkflowNodeResponse(
                n.getId(), n.getWorkflowDefinitionId(), n.getNodeCode(), n.getNodeName(),
                n.getNodeType(), n.getRelatedJobCode(), n.getRelatedPipelineCode(),
                n.getWorkerGroup(), n.getWindowCode(), n.getNodeOrder(),
                n.getRetryPolicy(), n.getRetryMaxCount(), n.getTimeoutSeconds(),
                n.getNodeParams(), n.getEnabled(), n.getCreatedAt(), n.getUpdatedAt());
    }

    private ConsoleWorkflowEdgeResponse toEdgeResponse(WorkflowEdgeEntity e) {
        return new ConsoleWorkflowEdgeResponse(
                e.getId(), e.getWorkflowDefinitionId(), e.getFromNodeCode(), e.getToNodeCode(),
                e.getEdgeType(), e.getConditionExpr(), e.getEnabled(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private List<String> validateDag(List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
        List<String> errors = new ArrayList<>();
        Set<String> nodeCodes = new HashSet<>();
        List<String> startNodes = new ArrayList<>();
        List<String> endNodes = new ArrayList<>();

        for (WorkflowNodeEntity n : nodes) {
            nodeCodes.add(n.getNodeCode());
            if ("START".equalsIgnoreCase(n.getNodeType())) {
                startNodes.add(n.getNodeCode());
            }
            if ("END".equalsIgnoreCase(n.getNodeType())) {
                endNodes.add(n.getNodeCode());
            }
        }

        if (startNodes.isEmpty()) {
            errors.add("Missing START node");
        } else if (startNodes.size() > 1) {
            errors.add("Multiple START nodes found: " + startNodes);
        }

        if (endNodes.isEmpty()) {
            errors.add("Missing END node");
        } else if (endNodes.size() > 1) {
            errors.add("Multiple END nodes found: " + endNodes);
        }

        for (WorkflowEdgeEntity e : edges) {
            if (!nodeCodes.contains(e.getFromNodeCode())) {
                errors.add("Edge references non-existent source node: " + e.getFromNodeCode());
            }
            if (!nodeCodes.contains(e.getToNodeCode())) {
                errors.add("Edge references non-existent target node: " + e.getToNodeCode());
            }
        }

        // Build adjacency for cycle detection and reachability
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, List<String>> reverseAdj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String code : nodeCodes) {
            adj.put(code, new ArrayList<>());
            reverseAdj.put(code, new ArrayList<>());
            inDegree.put(code, 0);
        }
        for (WorkflowEdgeEntity e : edges) {
            if (nodeCodes.contains(e.getFromNodeCode()) && nodeCodes.contains(e.getToNodeCode())) {
                adj.get(e.getFromNodeCode()).add(e.getToNodeCode());
                reverseAdj.get(e.getToNodeCode()).add(e.getFromNodeCode());
                inDegree.merge(e.getToNodeCode(), 1, Integer::sum);
            }
        }

        // Kahn's algorithm for cycle detection
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int visited = 0;
        Set<String> reachableFromStart = new HashSet<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : adj.get(cur)) {
                int deg = inDegree.get(next) - 1;
                inDegree.put(next, deg);
                if (deg == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < nodeCodes.size()) {
            errors.add("Cycle detected in workflow DAG");
        }

        // Reachability from START
        if (startNodes.size() == 1) {
            String startCode = startNodes.get(0);
            bfs(startCode, adj, reachableFromStart);
            for (String code : nodeCodes) {
                if (!"START".equalsIgnoreCase(nodeTypeByCode(nodes, code))
                        && !reachableFromStart.contains(code)) {
                    errors.add("Node not reachable from START: " + code);
                }
            }
        }

        // END node should have incoming edges
        if (endNodes.size() == 1) {
            String endCode = endNodes.get(0);
            Set<String> reachableToEnd = new HashSet<>();
            bfs(endCode, reverseAdj, reachableToEnd);
            for (String code : nodeCodes) {
                if (!"END".equalsIgnoreCase(nodeTypeByCode(nodes, code))
                        && !reachableToEnd.contains(code)) {
                    errors.add("Node cannot reach END: " + code);
                }
            }
        }

        return errors;
    }

    private void bfs(String start, Map<String, List<String>> adj, Set<String> visited) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
    }

    private String nodeTypeByCode(List<WorkflowNodeEntity> nodes, String code) {
        for (WorkflowNodeEntity n : nodes) {
            if (n.getNodeCode().equals(code)) {
                return n.getNodeType();
            }
        }
        return null;
    }
}
