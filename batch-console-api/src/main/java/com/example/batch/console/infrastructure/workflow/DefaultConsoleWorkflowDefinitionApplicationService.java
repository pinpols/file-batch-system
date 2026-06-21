package com.example.batch.console.infrastructure.workflow;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.job.infrastructure.DefaultConsoleJobDefinitionApplicationService;
import com.example.batch.console.domain.job.mapper.JobDefinitionMapper;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.workflow.application.WorkflowDesignLockService;
import com.example.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import com.example.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import com.example.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.workflow.query.WorkflowNodeQuery;
import com.example.batch.console.domain.workflow.validation.WorkflowDagValidator;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
import com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *       避免遗留异常数据（调用方全量提交新 DAG 即可，不必增量 diff）。
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
  private final WorkflowDefinitionVersionMapper versionMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;
  private final WorkflowDesignLockService designLockService;
  private final WorkflowDagValidator dagValidator;
  private final ObjectMapper objectMapper;

  // 反序列化历史 nodes_json / edges_json — JSONB 全文 → entity list
  private static final TypeReference<List<WorkflowNodeEntity>> NODE_LIST_TYPE =
      new TypeReference<>() {};
  private static final TypeReference<List<WorkflowEdgeEntity>> EDGE_LIST_TYPE =
      new TypeReference<>() {};

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

    // BE 兜底:与 fullUpdate 同源的 DAG 拓扑 + 引用完整性 + 跨 workflow 环校验。
    // 防脚本 / 旧前端经 create 入口写入单 workflow 环、跨 workflow 嵌套环或坏引用(绕过 fullUpdate 的校验)。
    dagValidator.validate(resolvedTenant, request);
    dagValidator.validateNoCrossWorkflowCycle(resolvedTenant, request.getWorkflowCode(), request);

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

    // BE 兜底:与 fullUpdate 同源校验。update 不改 workflowCode,用持久化 def 的 code 作环检测 root。
    dagValidator.validate(resolvedTenant, request);
    dagValidator.validateNoCrossWorkflowCycle(resolvedTenant, def.getWorkflowCode(), request);

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
  public WorkflowDefinitionDetailResponse fullUpdate(
      Long id, WorkflowDefinitionFullUpdateRequest request, String currentUser) {
    WorkflowDefinitionSaveRequest body = request.getDefinition();
    String resolvedTenant = tenantGuard.resolveTenant(body.getTenantId());

    WorkflowDefinitionEntity def =
        Guard.requireFound(
            definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);

    // workflowCode 不可改:保持持久化引用稳定(workflow_node.workflow_definition_id 等下游不感知 code 变更)
    if (body.getWorkflowCode() != null && !body.getWorkflowCode().equals(def.getWorkflowCode())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.workflow_full_update.code_immutable",
          def.getWorkflowCode());
    }

    // 锁归属校验:必须当前 user 持锁(锁不存在 → CONFLICT 让前端重新申请;别人持锁 → CONFLICT 含 lockedBy)
    LockHolder holder = designLockService.currentHolder(resolvedTenant, id);
    if (holder == null) {
      throw BizException.of(ResultCode.CONFLICT, "error.workflow_design_lock.required");
    }
    if (!holder.lockedBy().equals(currentUser)) {
      throw BizException.of(
          ResultCode.CONFLICT, "error.workflow_design_lock.held_by_other", holder.lockedBy());
    }

    // BE 兜底:DAG 拓扑 + 引用完整性校验(范围 = 拓扑;业务对错见 ADR-021,不在此处)
    dagValidator.validate(resolvedTenant, body);
    // 跨 workflow 嵌套环检测:JOB 节点指向 WORKFLOW 类型 job 时,配置期就拦住 A→B→A / 自引用
    // (workflowCode 不可变,用持久化 def 的 code 作 root)。运行期另有 ChildJobLaunchSupport 兜底。
    dagValidator.validateNoCrossWorkflowCycle(resolvedTenant, def.getWorkflowCode(), body);

    int rows =
        definitionMapper.updateAndBumpVersion(
            resolvedTenant,
            id,
            request.getExpectedVersion(),
            body.getWorkflowName(),
            body.getWorkflowType(),
            body.getEnabled() != null ? body.getEnabled() : def.getEnabled());
    if (rows == 0) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.workflow_full_update.version_conflict",
          request.getExpectedVersion(),
          def.getVersion());
    }

    nodeMapper.deleteByWorkflowDefinitionId(id);
    edgeMapper.deleteByWorkflowDefinitionId(id);
    upsertNodesAndEdges(resolvedTenant, id, body);

    // 同事务追加版本快照(workflow-dag-designer Polish):新 version = 旧 version + 1。
    // 序列化用 ObjectMapper 写当前持久化后的 entity list — 与 detail 读路径一致,
    // 避免基于 request DTO 序列化导致下游字段差异。
    Integer newVersion = def.getVersion() + 1;
    appendVersionSnapshot(resolvedTenant, id, def.getWorkflowCode(), newVersion, body, currentUser);

    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-full-updated");

    return loadDetail(resolvedTenant, id);
  }

  private void appendVersionSnapshot(
      String tenantId,
      Long definitionId,
      String workflowCode,
      Integer newVersion,
      WorkflowDefinitionSaveRequest body,
      String savedBy) {
    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, definitionId, null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(tenantId, definitionId, null));
    WorkflowDefinitionVersionInsertParam param = new WorkflowDefinitionVersionInsertParam();
    param.setTenantId(tenantId);
    param.setWorkflowDefinitionId(definitionId);
    param.setWorkflowCode(workflowCode);
    param.setVersion(newVersion);
    param.setWorkflowName(body.getWorkflowName());
    param.setWorkflowType(body.getWorkflowType());
    param.setEnabled(body.getEnabled());
    try {
      param.setNodesJson(objectMapper.writeValueAsString(nodes));
      param.setEdgesJson(objectMapper.writeValueAsString(edges));
    } catch (JsonProcessingException e) {
      // 主路径已持久化成功;序列化失败属编程错(entity 字段全 Jackson-friendly POJO),
      // 走 BizException 让事务回滚,避免主表 + 历史表分裂。
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.workflow.version_snapshot.serialize_failed",
          e.getMessage());
    }
    param.setSavedBy(savedBy);
    // summary 暂留 null(FE 未提交字段,Spike 不展示)
    versionMapper.insertVersionSnapshot(param);
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

  // 版本列表 / 版本详情真实实现见文件末尾的 listVersions / getVersion(V167 历史表闭环)。

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

  // ─── workflow-dag-designer Polish: 版本列表 / 单版本读取 ──────────────────────────

  @Override
  public List<WorkflowDefinitionVersionSummaryResponse> listVersions(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def =
        Guard.requireFound(
            definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);

    List<WorkflowDefinitionVersionEntity> rows =
        versionMapper.listByDefinitionId(resolvedTenant, id);
    if (rows.isEmpty()) {
      // 降级路径:历史表无数据(刚迁移后 / 从未 fullUpdate)→ 单条返回主表 current,
      // 与 PR #370 行为一致,FE diff 页可降级显示"当前 vs 空"。
      return List.of(
          new WorkflowDefinitionVersionSummaryResponse(
              def.getVersion(), null, def.getUpdatedAt(), null, true));
    }
    Integer currentVersion = def.getVersion();
    return rows.stream()
        .map(
            r ->
                new WorkflowDefinitionVersionSummaryResponse(
                    r.getVersion(),
                    r.getSavedBy(),
                    r.getSavedAt(),
                    r.getSummary(),
                    r.getVersion().equals(currentVersion)))
        .toList();
  }

  @Override
  public WorkflowDefinitionDetailResponse getVersion(Long id, String tenantId, Integer version) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def =
        Guard.requireFound(
            definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);
    if (version == null || version.equals(def.getVersion())) {
      // 当前 version → 主表 + 关联节点边(loadDetail 路径)
      return loadDetail(resolvedTenant, id);
    }
    WorkflowDefinitionVersionEntity snapshot =
        versionMapper.findByDefinitionIdAndVersion(resolvedTenant, id, version);
    if (snapshot == null) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow_version.not_found", id, version, def.getVersion());
    }
    List<WorkflowNodeEntity> nodes = readNodesJson(snapshot.getNodesJson());
    List<WorkflowEdgeEntity> edges = readEdgesJson(snapshot.getEdgesJson());
    return new WorkflowDefinitionDetailResponse(
        def.getId(),
        def.getTenantId(),
        snapshot.getWorkflowCode(),
        snapshot.getWorkflowName(),
        snapshot.getWorkflowType(),
        snapshot.getVersion(),
        snapshot.getEnabled(),
        def.getDescription(),
        def.getCreatedAt(),
        snapshot.getSavedAt(),
        nodes.stream().map(this::toNodeResponse).toList(),
        edges.stream().map(this::toEdgeResponse).toList());
  }

  private List<WorkflowNodeEntity> readNodesJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, NODE_LIST_TYPE);
    } catch (JsonProcessingException e) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.workflow.version_snapshot.deserialize_failed",
          e.getMessage());
    }
  }

  private List<WorkflowEdgeEntity> readEdgesJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, EDGE_LIST_TYPE);
    } catch (JsonProcessingException e) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.workflow.version_snapshot.deserialize_failed",
          e.getMessage());
    }
  }
}
