package com.example.batch.console.infrastructure.workflow;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import com.example.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.workflow.query.WorkflowNodeQuery;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import com.example.batch.console.infrastructure.job.DefaultConsoleJobDefinitionApplicationService;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
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

    upsertNodesAndEdges(resolvedTenant, entity.getId(), request);
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
    upsertNodesAndEdges(resolvedTenant, id, request);
    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-updated");

    return loadDetail(resolvedTenant, id);
  }

  @Override
  @Transactional
  public void toggleEnabled(Long id, String tenantId, Boolean enabled) {
    // @Transactional 必需:evictWorkflowDefinition 走 afterCommit 钩子,无事务时退化为立即 DEL,
    // 造成"删缓存 → 事务未提交 → 读者填回旧值 → 事务提交"的不一致窗口。与 create/update 对齐。
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

    List<DagValidationResult.Finding> findings = validateDag(resolvedTenant, nodes, edges);
    List<String> errors =
        findings.stream()
            .filter(f -> DagValidationResult.Finding.LEVEL_ERROR.equals(f.level()))
            .map(DagValidationResult.Finding::message)
            .toList();
    return new DagValidationResult(errors.isEmpty(), errors, findings);
  }

  private void upsertNodesAndEdges(
      String tenantId, Long definitionId, WorkflowDefinitionSaveRequest request) {
    if (request.getNodes() != null) {
      for (WorkflowDefinitionSaveRequest.NodeItem n : request.getNodes()) {
        WorkflowNodeUpsertParam param = new WorkflowNodeUpsertParam();
        param.setTenantId(tenantId);
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
        param.setTenantId(tenantId);
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
        n.getUpdatedAt(),
        n.getCrossDayDependencies(),
        n.getCrossDayDependencyTimeoutSeconds());
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

  private List<DagValidationResult.Finding> validateDag(
      String tenantId, List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
    List<DagValidationResult.Finding> findings = new ArrayList<>();
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

    validateNodeReferences(findings, nodeCodes, startNodes, endNodes, edges);
    validateJobNodeReferences(findings, tenantId, nodes);
    validateConditionEdges(findings, edges);
    DagAdjacency dag = buildAdjacency(nodeCodes, edges);
    detectCycles(findings, dag, nodeCodes);
    validateReachability(findings, nodes, nodeCodes, startNodes, endNodes, dag);

    return findings;
  }

  // WF-design-5: JOB 节点必须填 related_job_code 且对应 job_definition 必须存在且 enabled=true。
  private void validateJobNodeReferences(
      List<DagValidationResult.Finding> findings, String tenantId, List<WorkflowNodeEntity> nodes) {
    for (WorkflowNodeEntity n : nodes) {
      if (!"JOB".equalsIgnoreCase(n.getNodeType())) {
        continue;
      }
      String jobCode = n.getRelatedJobCode();
      if (jobCode == null || jobCode.isBlank()) {
        findings.add(
            DagValidationResult.Finding.error(
                "JOB_REF_MISSING",
                "JOB node missing related_job_code: " + n.getNodeCode(),
                n.getNodeCode(),
                null));
        continue;
      }
      var jobDef = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
      if (jobDef == null) {
        findings.add(
            DagValidationResult.Finding.error(
                "JOB_REF_NOT_FOUND",
                "JOB node "
                    + n.getNodeCode()
                    + " references non-existent job_definition: "
                    + jobCode,
                n.getNodeCode(),
                null));
        continue;
      }
      if (Boolean.FALSE.equals(jobDef.getEnabled())) {
        findings.add(
            DagValidationResult.Finding.error(
                "JOB_REF_DISABLED",
                "JOB node " + n.getNodeCode() + " references disabled job_definition: " + jobCode,
                n.getNodeCode(),
                null));
      }
    }
  }

  // WF-design-6: edge_type=CONDITION 必须填 condition_expr。
  private void validateConditionEdges(
      List<DagValidationResult.Finding> findings, List<WorkflowEdgeEntity> edges) {
    for (WorkflowEdgeEntity e : edges) {
      if (!"CONDITION".equalsIgnoreCase(e.getEdgeType())) {
        continue;
      }
      if (e.getConditionExpr() == null || e.getConditionExpr().isBlank()) {
        findings.add(
            DagValidationResult.Finding.error(
                "EDGE_CONDITION_MISSING_EXPR",
                "CONDITION edge missing condition_expr: "
                    + e.getFromNodeCode()
                    + " -> "
                    + e.getToNodeCode(),
                null,
                edgeIdOf(e)));
      }
    }
  }

  private void validateNodeReferences(
      List<DagValidationResult.Finding> findings,
      Set<String> nodeCodes,
      List<String> startNodes,
      List<String> endNodes,
      List<WorkflowEdgeEntity> edges) {
    if (startNodes.isEmpty()) {
      findings.add(
          DagValidationResult.Finding.error("MISSING_START", "Missing START node", null, null));
    } else if (startNodes.size() > 1) {
      // 多个 START 时把第 2 个之后的位置标到具体 node 以便前端高亮
      for (int i = 1; i < startNodes.size(); i++) {
        findings.add(
            DagValidationResult.Finding.error(
                "MULTIPLE_START",
                "Multiple START nodes found: " + startNodes,
                startNodes.get(i),
                null));
      }
    }

    if (endNodes.isEmpty()) {
      findings.add(
          DagValidationResult.Finding.error("MISSING_END", "Missing END node", null, null));
    } else if (endNodes.size() > 1) {
      for (int i = 1; i < endNodes.size(); i++) {
        findings.add(
            DagValidationResult.Finding.error(
                "MULTIPLE_END", "Multiple END nodes found: " + endNodes, endNodes.get(i), null));
      }
    }

    for (WorkflowEdgeEntity e : edges) {
      if (!nodeCodes.contains(e.getFromNodeCode())) {
        findings.add(
            DagValidationResult.Finding.error(
                "EDGE_SOURCE_MISSING",
                "Edge references non-existent source node: " + e.getFromNodeCode(),
                null,
                edgeIdOf(e)));
      }
      if (!nodeCodes.contains(e.getToNodeCode())) {
        findings.add(
            DagValidationResult.Finding.error(
                "EDGE_TARGET_MISSING",
                "Edge references non-existent target node: " + e.getToNodeCode(),
                null,
                edgeIdOf(e)));
      }
    }
  }

  /** 边没有持久化的 string id，前端用 `${from}-${to}-${edgeType}` 拼，保持一致。 */
  private static String edgeIdOf(WorkflowEdgeEntity e) {
    return e.getFromNodeCode() + "-" + e.getToNodeCode() + "-" + e.getEdgeType();
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

  private void detectCycles(
      List<DagValidationResult.Finding> findings, DagAdjacency dag, Set<String> nodeCodes) {
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
      // 把仍有 inDegree > 0 的节点（在环里）逐个标出，便于前端高亮
      for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
        if (entry.getValue() > 0) {
          findings.add(
              DagValidationResult.Finding.error(
                  "CYCLE_DETECTED",
                  "Cycle detected in workflow DAG (node still has incoming edges)",
                  entry.getKey(),
                  null));
        }
      }
    }
  }

  private void validateReachability(
      List<DagValidationResult.Finding> findings,
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
          findings.add(
              DagValidationResult.Finding.error(
                  "UNREACHABLE_FROM_START", "Node not reachable from START: " + code, code, null));
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
          findings.add(
              DagValidationResult.Finding.error(
                  "CANNOT_REACH_END", "Node cannot reach END: " + code, code, null));
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
