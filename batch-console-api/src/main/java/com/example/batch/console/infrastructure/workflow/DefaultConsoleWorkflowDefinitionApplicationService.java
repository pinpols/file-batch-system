package com.example.batch.console.infrastructure.workflow;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.workflow.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.domain.param.WorkflowNodeUpsertParam;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import com.example.batch.console.infrastructure.job.DefaultConsoleJobDefinitionApplicationService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.request.workflow.WorkflowDefinitionSaveRequest;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowNodeResponse;
import com.example.batch.console.web.response.workflow.WorkflowDefinitionDetailResponse;
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
 * Workflow 定义的 CRUD + DAG 校验入口。
 *
 * <p>写操作模式（create / update / toggle）：
 *
 * <ul>
 *   <li>定义 + 节点 + 边在同一事务内 upsert；update 先 {@code deleteByWorkflowDefinitionId} 清空节点/边再重写，
 *       避免遗留脏数据（调用方全量提交新 DAG 即可，不必增量 diff）。
 *   <li>每次写都调 {@link ConsoleConfigCacheInvalidationService#evictWorkflowDefinition}， 保证
 *       orchestrator launch 时读到最新拓扑（与 {@link DefaultConsoleJobDefinitionApplicationService} 一致的
 *       缓存一致性协议）。
 *   <li>通过 {@link ConsoleRealtimeDomainEventPublisher#publishChanged} 广播 {@code
 *       workflow-definitions} 事件 实时刷新前端视图。
 * </ul>
 *
 * <p>{@link #validate} 执行完整 DAG 健康检查——在发布/前端可视化编辑前使用：
 *
 * <ol>
 *   <li><b>节点引用</b>：唯一 START / 唯一 END；边的 fromNodeCode / toNodeCode 存在于节点集。
 *   <li><b>无环</b>：Kahn 拓扑排序，若遍历数 &lt; 节点数则存在环。
 *   <li><b>可达性</b>：BFS 从 START 正向遍历 / 从 END 逆向遍历，所有非起止节点必须双向可达—— 孤立节点或"到不了 END"的死路都会被标记出来。
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowDefinitionApplicationService
    implements ConsoleWorkflowDefinitionApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String ERR_WORKFLOW_NOT_FOUND = "Workflow definition not found: ";

  private final WorkflowDefinitionMapper definitionMapper;
  private final WorkflowNodeMapper nodeMapper;
  private final WorkflowEdgeMapper edgeMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Override
  public WorkflowDefinitionDetailResponse getById(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def =
        Guard.requireFound(
            definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);
    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(resolvedTenant, def.getId(), null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(resolvedTenant, def.getId(), null));
    return toDetailResponse(def, nodes, edges);
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());

    WorkflowDefinitionEntity existing =
        definitionMapper.selectByUniqueKey(resolvedTenant, request.getWorkflowCode(), 1);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
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
    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, request.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-created");

    return loadDetail(resolvedTenant, entity.getId());
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse update(Long id, WorkflowDefinitionSaveRequest request) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());

    WorkflowDefinitionEntity def =
        Guard.requireFound(
            definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);

    definitionMapper.updateWorkflowDefinition(
        resolvedTenant,
        id,
        request.getWorkflowName(),
        request.getWorkflowType(),
        request.getEnabled());

    nodeMapper.deleteByWorkflowDefinitionId(id);
    edgeMapper.deleteByWorkflowDefinitionId(id);
    upsertNodesAndEdges(id, request);
    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-updated");

    return loadDetail(resolvedTenant, id);
  }

  @Override
  public void toggleEnabled(Long id, String tenantId, Boolean enabled) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    int rows = definitionMapper.toggleEnabled(resolvedTenant, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.not_found", id);
    }
    WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
    if (def != null) {
      cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    }
    publishRefresh(resolvedTenant, "workflow-definition-toggled");
  }

  @Override
  public DagValidationResult validate(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def =
        Guard.requireFound(
            definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);

    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(resolvedTenant, def.getId(), null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(resolvedTenant, def.getId(), null));

    List<String> errors = validateDag(resolvedTenant, nodes, edges);
    return new DagValidationResult(errors.isEmpty(), errors);
  }

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
    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, id, null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(tenantId, id, null));
    return toDetailResponse(def, nodes, edges);
  }

  private WorkflowDefinitionDetailResponse toDetailResponse(
      WorkflowDefinitionEntity def,
      List<WorkflowNodeEntity> nodes,
      List<WorkflowEdgeEntity> edges) {
    return new WorkflowDefinitionDetailResponse(
        def.getId(),
        def.getTenantId(),
        def.getWorkflowCode(),
        def.getWorkflowName(),
        def.getWorkflowType(),
        def.getVersion(),
        def.getEnabled(),
        def.getDescription(),
        def.getCreatedAt(),
        def.getUpdatedAt(),
        nodes.stream().map(this::toNodeResponse).toList(),
        edges.stream().map(this::toEdgeResponse).toList());
  }

  private ConsoleWorkflowNodeResponse toNodeResponse(WorkflowNodeEntity n) {
    return new ConsoleWorkflowNodeResponse(
        n.getId(),
        n.getWorkflowDefinitionId(),
        n.getNodeCode(),
        n.getNodeName(),
        n.getNodeType(),
        n.getRelatedJobCode(),
        n.getRelatedPipelineCode(),
        n.getWorkerGroup(),
        n.getWindowCode(),
        n.getNodeOrder(),
        n.getRetryPolicy(),
        n.getRetryMaxCount(),
        n.getTimeoutSeconds(),
        n.getNodeParams(),
        n.getEnabled(),
        n.getCreatedAt(),
        n.getUpdatedAt());
  }

  private ConsoleWorkflowEdgeResponse toEdgeResponse(WorkflowEdgeEntity e) {
    return new ConsoleWorkflowEdgeResponse(
        e.getId(),
        e.getWorkflowDefinitionId(),
        e.getFromNodeCode(),
        e.getToNodeCode(),
        e.getEdgeType(),
        e.getConditionExpr(),
        e.getEnabled(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }

  private List<String> validateDag(
      String tenantId, List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
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

    validateNodeReferences(errors, nodeCodes, startNodes, endNodes, edges);
    validateJobNodeReferences(errors, tenantId, nodes);
    validateConditionEdges(errors, edges);
    DagAdjacency dag = buildAdjacency(nodeCodes, edges);
    detectCycles(errors, dag, nodeCodes);
    validateReachability(errors, nodes, nodeCodes, startNodes, endNodes, dag);

    return errors;
  }

  // WF-design-5: JOB 节点必须填 related_job_code 且对应 job_definition 必须存在且 enabled=true。
  private void validateJobNodeReferences(
      List<String> errors, String tenantId, List<WorkflowNodeEntity> nodes) {
    for (WorkflowNodeEntity n : nodes) {
      if (!"JOB".equalsIgnoreCase(n.getNodeType())) {
        continue;
      }
      String jobCode = n.getRelatedJobCode();
      if (jobCode == null || jobCode.isBlank()) {
        errors.add("JOB node missing related_job_code: " + n.getNodeCode());
        continue;
      }
      var jobDef = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
      if (jobDef == null) {
        errors.add(
            "JOB node " + n.getNodeCode() + " references non-existent job_definition: " + jobCode);
        continue;
      }
      if (Boolean.FALSE.equals(jobDef.getEnabled())) {
        errors.add(
            "JOB node " + n.getNodeCode() + " references disabled job_definition: " + jobCode);
      }
    }
  }

  // WF-design-6: edge_type=CONDITION 必须填 condition_expr。
  private void validateConditionEdges(List<String> errors, List<WorkflowEdgeEntity> edges) {
    for (WorkflowEdgeEntity e : edges) {
      if (!"CONDITION".equalsIgnoreCase(e.getEdgeType())) {
        continue;
      }
      if (e.getConditionExpr() == null || e.getConditionExpr().isBlank()) {
        errors.add(
            "CONDITION edge missing condition_expr: "
                + e.getFromNodeCode()
                + " -> "
                + e.getToNodeCode());
      }
    }
  }

  private void validateNodeReferences(
      List<String> errors,
      Set<String> nodeCodes,
      List<String> startNodes,
      List<String> endNodes,
      List<WorkflowEdgeEntity> edges) {
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
  }

  private DagAdjacency buildAdjacency(Set<String> nodeCodes, List<WorkflowEdgeEntity> edges) {
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
    return new DagAdjacency(adj, reverseAdj, inDegree);
  }

  private void detectCycles(List<String> errors, DagAdjacency dag, Set<String> nodeCodes) {
    Deque<String> queue = new ArrayDeque<>();
    Map<String, Integer> inDegree = new HashMap<>(dag.inDegree());
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
      }
    }
    int visited = 0;
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      visited++;
      for (String next : dag.adj().get(cur)) {
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
  }

  private void validateReachability(
      List<String> errors,
      List<WorkflowNodeEntity> nodes,
      Set<String> nodeCodes,
      List<String> startNodes,
      List<String> endNodes,
      DagAdjacency dag) {
    if (startNodes.size() == 1) {
      String startCode = startNodes.get(0);
      Set<String> reachableFromStart = new HashSet<>();
      bfs(startCode, dag.adj(), reachableFromStart);
      for (String code : nodeCodes) {
        if (!"START".equalsIgnoreCase(nodeTypeByCode(nodes, code))
            && !reachableFromStart.contains(code)) {
          errors.add("Node not reachable from START: " + code);
        }
      }
    }

    if (endNodes.size() == 1) {
      String endCode = endNodes.get(0);
      Set<String> reachableToEnd = new HashSet<>();
      bfs(endCode, dag.reverseAdj(), reachableToEnd);
      for (String code : nodeCodes) {
        if (!"END".equalsIgnoreCase(nodeTypeByCode(nodes, code))
            && !reachableToEnd.contains(code)) {
          errors.add("Node cannot reach END: " + code);
        }
      }
    }
  }

  private record DagAdjacency(
      Map<String, List<String>> adj,
      Map<String, List<String>> reverseAdj,
      Map<String, Integer> inDegree) {}

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

  private void publishRefresh(String tenantId, String eventType) {
    domainEventPublisher.publishChanged(tenantId, "workflow-definitions", eventType);
  }
}
