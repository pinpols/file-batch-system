package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest.EdgeItem;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest.NodeItem;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowScheduleUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowDefinitionVersionSummaryResponse;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li>通过 {@link ConsoleRealtimeEventPort#publishChanged} 广播 {@code
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
public class DefaultWorkflowDefinitionService implements WorkflowDefinitionService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String ERR_WORKFLOW_NOT_FOUND = "Workflow definition not found: ";

  private final WorkflowDefinitionMapper definitionMapper;
  private final WorkflowNodeMapper nodeMapper;
  private final WorkflowEdgeMapper edgeMapper;
  private final WorkflowDefinitionVersionMapper versionMapper;
  private final ConsoleRealtimeEventPort domainEventPublisher;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;
  private final WorkflowDesignLockService designLockService;
  private final WorkflowDagValidator dagValidator;
  private final WorkflowDefinitionResponseAssembler responseAssembler;
  private final WorkflowDefinitionWriteSupport writeSupport;
  private final WorkflowDefinitionDagInspector dagInspector;
  private final JobResourceAssignmentGuard resourceAssignmentGuard;
  private final JobDefinitionMapper jobDefinitionMapper;

  @Override
  public WorkflowDefinitionDetailResponse getById(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def = Guard.requireFound(
        definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);
    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(resolvedTenant, def.getId(), null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(resolvedTenant, def.getId(), null));
    return responseAssembler.toDetailResponse(def, nodes, edges);
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());
    assertNodeRoutingOnCreate(request.getNodes());

    WorkflowDefinitionEntity existing =
        definitionMapper.selectByUniqueKey(resolvedTenant, request.getWorkflowCode(), 1);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "Workflow definition already exists: " + request.getWorkflowCode());
    }

    // BE 回退:与 fullUpdate 同源的 DAG 拓扑 + 引用完整性 + 跨 workflow 环校验。
    // 防脚本 / 旧前端经 create 入口写入单 workflow 环、跨 workflow 嵌套环或坏引用(绕过 fullUpdate 的校验)。
    dagValidator.validate(resolvedTenant, request);
    dagValidator.validateNoCrossWorkflowCycle(resolvedTenant, request.getWorkflowCode(), request);

    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
    entity.setTenantId(resolvedTenant);
    entity.setWorkflowCode(request.getWorkflowCode());
    entity.setWorkflowName(request.getWorkflowName());
    entity.setWorkflowType(request.getWorkflowType());
    applyScheduleConfiguration(entity, request, null);
    entity.setDependsOnJobCode(
        validateScheduleDependency(resolvedTenant, request.getDependsOnJobCode(), null));
    entity.setVersion(1);
    entity.setEnabled(request.getEnabled() == null || request.getEnabled());
    definitionMapper.insert(entity);

    writeSupport.upsertNodesAndEdges(resolvedTenant, entity.getId(), request);
    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, request.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-created");

    return loadDetail(resolvedTenant, entity.getId());
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse update(Long id, WorkflowDefinitionSaveRequest request) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());

    WorkflowDefinitionEntity def = Guard.requireFound(
        definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);
    assertManagedSingleJobGraph(resolvedTenant, def, request);
    applyScheduleConfiguration(def, request, def);
    String dependsOnJobCode = validateScheduleDependency(
        resolvedTenant, def.getDependsOnJobCode(), def.getSingleJobDefinitionId());
    assertAndPreserveNodeRouting(resolvedTenant, id, request.getNodes());

    // BE 回退:与 fullUpdate 同源校验。update 不改 workflowCode,用持久化 def 的 code 作环检测 root。
    dagValidator.validate(resolvedTenant, request);
    dagValidator.validateNoCrossWorkflowCycle(resolvedTenant, def.getWorkflowCode(), request);

    definitionMapper.updateWorkflowDefinition(
        resolvedTenant,
        id,
        request.getWorkflowName(),
        request.getWorkflowType(),
        request.getEnabled(),
        def.getScheduleEnabled(),
        def.getScheduleType(),
        def.getScheduleExpr(),
        def.getTimezone(),
        def.getTriggerMode(),
        def.getCalendarCode(),
        dependsOnJobCode);

    Map<String, String> existingNodeParams = writeSupport.readNodeParams(resolvedTenant, id);
    nodeMapper.deleteByWorkflowDefinitionId(id);
    edgeMapper.deleteByWorkflowDefinitionId(id);
    writeSupport.upsertNodesAndEdges(resolvedTenant, id, request, existingNodeParams);
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

    WorkflowDefinitionEntity def = Guard.requireFound(
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

    assertManagedSingleJobGraph(resolvedTenant, def, body);
    applyScheduleConfiguration(def, body, def);
    String dependsOnJobCode = validateScheduleDependency(
        resolvedTenant, def.getDependsOnJobCode(), def.getSingleJobDefinitionId());
    assertAndPreserveNodeRouting(resolvedTenant, id, body.getNodes());

    // BE 回退:DAG 拓扑 + 引用完整性校验(范围 = 拓扑;业务对错见 ADR-021,不在此处)
    dagValidator.validate(resolvedTenant, body);
    // 跨 workflow 嵌套环检测:JOB 节点指向 WORKFLOW 类型 job 时,配置期就拦住 A→B→A / 自引用
    // (workflowCode 不可变,用持久化 def 的 code 作 root)。运行期另有 ChildJobLaunchSupport 回退。
    dagValidator.validateNoCrossWorkflowCycle(resolvedTenant, def.getWorkflowCode(), body);

    int rows = definitionMapper.updateAndBumpVersion(
        resolvedTenant,
        id,
        request.getExpectedVersion(),
        body.getWorkflowName(),
        body.getWorkflowType(),
        body.getEnabled() != null ? body.getEnabled() : def.getEnabled(),
        def.getScheduleEnabled(),
        def.getScheduleType(),
        def.getScheduleExpr(),
        def.getTimezone(),
        def.getTriggerMode(),
        def.getCalendarCode(),
        dependsOnJobCode);
    if (rows == 0) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.workflow_full_update.version_conflict",
          request.getExpectedVersion(),
          def.getVersion());
    }

    Map<String, String> existingNodeParams = writeSupport.readNodeParams(resolvedTenant, id);
    nodeMapper.deleteByWorkflowDefinitionId(id);
    edgeMapper.deleteByWorkflowDefinitionId(id);
    writeSupport.upsertNodesAndEdges(resolvedTenant, id, body, existingNodeParams);

    // 同事务追加版本快照(workflow-dag-designer Polish):新 version = 旧 version + 1。
    // 版本协作者序列化当前持久化后的 entity list — 与 detail 读路径一致，
    // 避免基于 request DTO 序列化导致下游字段差异。
    Integer newVersion = def.getVersion() + 1;
    writeSupport.appendVersionSnapshot(
        resolvedTenant,
        id,
        def.getWorkflowCode(),
        newVersion,
        body,
        def.getScheduleEnabled(),
        currentUser);

    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-full-updated");

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
  @Transactional
  public void toggleScheduleEnabled(Long id, String tenantId, Boolean scheduleEnabled) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    int rows = definitionMapper.toggleScheduleEnabled(resolvedTenant, id, scheduleEnabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.not_found", id);
    }
    WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
    if (def != null) {
      cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    }
    publishRefresh(resolvedTenant, "workflow-schedule-toggled");
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse updateSchedule(
      Long id, WorkflowScheduleUpdateRequest request, String currentUser) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());
    WorkflowDefinitionEntity current = Guard.requireFound(
        definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);
    String dependsOnJobCode = validateScheduleDependency(
        resolvedTenant, request.getDependsOnJobCode(), current.getSingleJobDefinitionId());
    String scheduleType = request.getScheduleType().trim();
    String triggerMode = Texts.hasText(request.getTriggerMode())
        ? request.getTriggerMode().trim()
        : defaultTriggerMode(scheduleType);
    String scheduleExpr = "CRON".equals(scheduleType) || "FIXED_RATE".equals(scheduleType)
        ? request.getScheduleExpr()
        : null;
    int rows = definitionMapper.updateScheduleConfiguration(
        resolvedTenant,
        id,
        request.getExpectedVersion(),
        scheduleType,
        scheduleExpr,
        request.getTimezone(),
        triggerMode,
        request.getCalendarCode(),
        dependsOnJobCode);
    if (rows == 0) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.workflow_full_update.version_conflict",
          request.getExpectedVersion(),
          current.getVersion());
    }
    WorkflowDefinitionEntity updated = Guard.requireFound(
        definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);
    WorkflowDefinitionSaveRequest snapshot = new WorkflowDefinitionSaveRequest();
    snapshot.setWorkflowName(updated.getWorkflowName());
    snapshot.setWorkflowType(updated.getWorkflowType());
    snapshot.setEnabled(updated.getEnabled());
    snapshot.setScheduleType(updated.getScheduleType());
    snapshot.setScheduleExpr(updated.getScheduleExpr());
    snapshot.setTimezone(updated.getTimezone());
    snapshot.setTriggerMode(updated.getTriggerMode());
    snapshot.setCalendarCode(updated.getCalendarCode());
    snapshot.setDependsOnJobCode(updated.getDependsOnJobCode());
    writeSupport.appendVersionSnapshot(
        resolvedTenant,
        id,
        updated.getWorkflowCode(),
        updated.getVersion(),
        snapshot,
        updated.getScheduleEnabled(),
        currentUser);
    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, updated.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-schedule-updated");
    return loadDetail(resolvedTenant, id);
  }

  private String validateScheduleDependency(
      String tenantId, String requestedJobCode, Long managedJobDefinitionId) {
    if (!Texts.hasText(requestedJobCode)) {
      return null;
    }
    String jobCode = requestedJobCode.trim();
    JobDefinitionEntity upstream = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
    if (upstream == null) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow.schedule_dependency_job_not_found", jobCode);
    }
    if (managedJobDefinitionId != null && managedJobDefinitionId.equals(upstream.getId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.schedule_self_dependency");
    }
    return jobCode;
  }

  @Override
  public DagValidationResult validate(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def = Guard.requireFound(
        definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);

    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(resolvedTenant, def.getId(), null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(resolvedTenant, def.getId(), null));

    List<DagValidationResult.Finding> findings = dagInspector.inspect(resolvedTenant, nodes, edges);
    List<String> errors = findings.stream()
        .filter(f -> DagValidationResult.Finding.LEVEL_ERROR.equals(f.level()))
        .map(DagValidationResult.Finding::message)
        .toList();
    return new DagValidationResult(errors.isEmpty(), errors, findings);
  }

  // 版本列表 / 版本详情真实实现见文件末尾的 listVersions / getVersion(V167 历史表闭环)。

  private WorkflowDefinitionDetailResponse loadDetail(String tenantId, Long id) {
    WorkflowDefinitionEntity def = definitionMapper.selectById(tenantId, id);
    List<WorkflowNodeEntity> nodes =
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, id, null));
    List<WorkflowEdgeEntity> edges =
        edgeMapper.selectByQuery(WorkflowEdgeQuery.ofDefinition(tenantId, id, null));
    return responseAssembler.toDetailResponse(def, nodes, edges);
  }

  private void assertNodeRoutingOnCreate(List<WorkflowDefinitionSaveRequest.NodeItem> nodes) {
    if (EmptyChecks.isNull(nodes)) {
      return;
    }
    for (WorkflowDefinitionSaveRequest.NodeItem node : nodes) {
      if (Texts.hasText(node.getWorkerGroup())) {
        resourceAssignmentGuard.assertCreateAllowed(null, node.getWorkerGroup());
      }
    }
  }

  /** Managed single-job workflows may change metadata, but their system graph is immutable. */
  private void assertManagedSingleJobGraph(
      String tenantId, WorkflowDefinitionEntity definition, WorkflowDefinitionSaveRequest request) {
    if (definition.getSingleJobDefinitionId() == null) {
      return;
    }
    List<NodeItem> submittedNodes = request.getNodes();
    List<EdgeItem> submittedEdges = request.getEdges();
    if (!"DAG".equals(definition.getWorkflowType())
        || (request.getWorkflowType() != null && !"DAG".equals(request.getWorkflowType()))
        || submittedNodes == null
        || submittedNodes.size() != 3
        || submittedNodes.stream().anyMatch(Objects::isNull)
        || submittedEdges == null
        || submittedEdges.size() != 2
        || submittedEdges.stream().anyMatch(Objects::isNull)) {
      throw managedGraphConflict();
    }

    Map<String, NodeItem> nodesByCode = new HashMap<>();
    for (NodeItem node : submittedNodes) {
      if (nodesByCode.putIfAbsent(node.getNodeCode(), node) != null) {
        throw managedGraphConflict();
      }
    }
    String jobCode =
        nodeMapper
            .selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, definition.getId(), null))
            .stream()
            .filter(node -> "JOB".equals(node.getNodeType()))
            .map(WorkflowNodeEntity::getRelatedJobCode)
            .filter(Texts::hasText)
            .findFirst()
            .orElseThrow(DefaultWorkflowDefinitionService::managedGraphConflict);
    if (!isManagedNode(nodesByCode.get("START"), "START", "START", 0, null)
        || !isManagedNode(nodesByCode.get("JOB"), "JOB", "JOB", 1, jobCode)
        || !isManagedNode(nodesByCode.get("END"), "END", "END", 2, null)) {
      throw managedGraphConflict();
    }
    Set<String> edges = submittedEdges.stream()
        .map(edge -> edge.getFromNodeCode() + ">" + edge.getToNodeCode() + ":"
            + (edge.getEdgeType() == null ? "SUCCESS" : edge.getEdgeType()))
        .collect(java.util.stream.Collectors.toSet());
    boolean defaultEdges = edges.size() == 2
        && edges.contains("START>JOB:SUCCESS")
        && edges.contains("JOB>END:SUCCESS")
        && submittedEdges.stream()
            .allMatch(edge -> !Texts.hasText(edge.getConditionExpr())
                && !Boolean.FALSE.equals(edge.getEnabled()));
    if (!defaultEdges) {
      throw managedGraphConflict();
    }
  }

  private static boolean isManagedNode(
      NodeItem node, String nodeCode, String nodeType, int nodeOrder, String relatedJobCode) {
    return node != null
        && nodeCode.equals(node.getNodeCode())
        && nodeType.equals(node.getNodeType())
        && Objects.equals(relatedJobCode, node.getRelatedJobCode())
        && Objects.equals(nodeOrder, node.getNodeOrder())
        && !Texts.hasText(node.getRelatedPipelineCode())
        && !Texts.hasText(node.getWorkerGroup())
        && !Texts.hasText(node.getNodeParams())
        && !Texts.hasText(node.getCrossDayDependencies())
        && (!Texts.hasText(node.getRetryPolicy()) || "NONE".equals(node.getRetryPolicy()))
        && (node.getRetryMaxCount() == null || node.getRetryMaxCount() == 0)
        && (node.getTimeoutSeconds() == null || node.getTimeoutSeconds() == 0)
        && !Boolean.FALSE.equals(node.getEnabled());
  }

  private static BizException managedGraphConflict() {
    return BizException.of(ResultCode.CONFLICT, "error.workflow.single_job_graph_managed");
  }

  /** Keep schedule ownership in Workflow while old Console clients omit the new fields. */
  private static void applyScheduleConfiguration(
      WorkflowDefinitionEntity target,
      WorkflowDefinitionSaveRequest request,
      WorkflowDefinitionEntity existing) {
    String requestedType = request.getScheduleType();
    if (!Texts.hasText(requestedType) && existing != null) {
      request.setScheduleType(existing.getScheduleType());
      request.setScheduleExpr(existing.getScheduleExpr());
      request.setTimezone(existing.getTimezone());
      request.setTriggerMode(existing.getTriggerMode());
      request.setCalendarCode(existing.getCalendarCode());
      request.setDependsOnJobCode(existing.getDependsOnJobCode());
      return;
    }

    String scheduleType = Texts.hasText(requestedType) ? requestedType.trim() : "MANUAL";
    String triggerMode = request.getTriggerMode();
    if (!Texts.hasText(triggerMode)) {
      triggerMode = existing != null && scheduleType.equals(existing.getScheduleType())
          ? existing.getTriggerMode()
          : defaultTriggerMode(scheduleType);
    }
    target.setScheduleType(scheduleType);
    target.setScheduleExpr("MANUAL".equals(scheduleType) ? null : request.getScheduleExpr());
    target.setTimezone(request.getTimezone());
    target.setTriggerMode(triggerMode);
    target.setCalendarCode(request.getCalendarCode());
    target.setDependsOnJobCode(request.getDependsOnJobCode());
    request.setScheduleType(scheduleType);
    request.setScheduleExpr(target.getScheduleExpr());
    request.setTriggerMode(triggerMode);
  }

  private static String defaultTriggerMode(String scheduleType) {
    return switch (scheduleType) {
      case "EVENT" -> "EVENT";
      case "CRON", "FIXED_RATE", "ONE_TIME" -> "SCHEDULED";
      default -> "MANUAL";
    };
  }

  private void assertAndPreserveNodeRouting(
      String tenantId, Long definitionId, List<WorkflowDefinitionSaveRequest.NodeItem> nodes) {
    if (EmptyChecks.isNull(nodes)) {
      return;
    }
    Map<String, String> existingGroups = new HashMap<>();
    for (WorkflowNodeEntity node :
        nodeMapper.selectByQuery(WorkflowNodeQuery.ofDefinition(tenantId, definitionId, null))) {
      existingGroups.put(node.getNodeCode(), node.getWorkerGroup());
    }
    boolean hasRouting = existingGroups.values().stream().anyMatch(Texts::hasText)
        || nodes.stream().anyMatch(node -> Texts.hasText(node.getWorkerGroup()));
    if (!hasRouting || !resourceAssignmentGuard.shouldPreserveExistingRouting()) {
      return;
    }
    for (WorkflowDefinitionSaveRequest.NodeItem node : nodes) {
      String existingGroup = existingGroups.get(node.getNodeCode());
      resourceAssignmentGuard.assertWorkerGroupUpdateAllowed(node.getWorkerGroup(), existingGroup);
      if (EmptyChecks.isNull(node.getWorkerGroup()) && EmptyChecks.isNotNull(existingGroup)) {
        node.setWorkerGroup(existingGroup);
      }
    }
  }

  private void publishRefresh(String tenantId, String eventType) {
    domainEventPublisher.publishChanged(tenantId, "workflow-definitions", eventType);
  }

  // ─── workflow-dag-designer Polish: 版本列表 / 单版本读取 ──────────────────────────

  @Override
  public List<WorkflowDefinitionVersionSummaryResponse> listVersions(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity def = Guard.requireFound(
        definitionMapper.selectById(resolvedTenant, id), ERR_WORKFLOW_NOT_FOUND + id);

    List<WorkflowDefinitionVersionEntity> rows =
        versionMapper.listByDefinitionId(resolvedTenant, id);
    if (rows.isEmpty()) {
      // 降级路径:历史表无数据(刚迁移后 / 从未 fullUpdate)→ 单条返回主表 current,
      // 与 PR #370 行为一致,FE diff 页可降级显示"当前 vs 空"。
      return List.of(new WorkflowDefinitionVersionSummaryResponse(
          def.getVersion(), null, def.getUpdatedAt(), null, true));
    }
    Integer currentVersion = def.getVersion();
    return rows.stream()
        .map(r -> new WorkflowDefinitionVersionSummaryResponse(
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
    WorkflowDefinitionEntity def = Guard.requireFound(
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
    List<WorkflowNodeEntity> nodes = writeSupport.readNodesJson(snapshot.getNodesJson());
    List<WorkflowEdgeEntity> edges = writeSupport.readEdgesJson(snapshot.getEdgesJson());
    return responseAssembler.toHistoricalDetailResponse(def, snapshot, nodes, edges);
  }
}
