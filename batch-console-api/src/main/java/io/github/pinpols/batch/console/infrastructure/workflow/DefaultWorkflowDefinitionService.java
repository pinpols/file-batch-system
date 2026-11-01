package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import io.github.pinpols.batch.console.domain.workflow.application.command.WorkflowVersionSnapshotCommand;
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
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowScheduleUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.domain.workflow.validation.WorkflowDagValidator;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
  private final BusinessCalendarMapper businessCalendarMapper;

  @Override
  public WorkflowDefinitionDetailResponse getById(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity loadedDefinition = definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity def =
        Guard.requireFound(loadedDefinition, ERR_WORKFLOW_NOT_FOUND + id);
    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(resolvedTenant, def.getId(), null);
    List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(nodeQuery);
    WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.ofDefinition(resolvedTenant, def.getId(), null);
    List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(edgeQuery);
    return responseAssembler.toDetailResponse(def, nodes, edges);
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());
    assertNodeRoutingOnCreate(request.getNodes());

    // 编辑会递增当前行版本，创建去重仍须按稳定编码检查，而不是只检查版本1。
    WorkflowDefinitionEntity existing =
        definitionMapper.selectLatestByCode(resolvedTenant, request.getWorkflowCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "Workflow definition already exists: " + request.getWorkflowCode());
    }

    // BE 回退:与 fullUpdate 同源的 DAG 拓扑 + 引用完整性校验,防止脚本或旧客户端绕过前端校验。
    dagValidator.validate(resolvedTenant, request);
    String validatedCalendarCode =
        validateCalendarReference(resolvedTenant, request.getCalendarCode());
    request.setCalendarCode(validatedCalendarCode);

    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
    entity.setTenantId(resolvedTenant);
    entity.setWorkflowCode(request.getWorkflowCode());
    entity.setWorkflowName(request.getWorkflowName());
    entity.setWorkflowType(request.getWorkflowType());
    applyScheduleConfiguration(entity, request, null);
    String validatedScheduleDependency =
        validateScheduleDependency(resolvedTenant, request.getDependsOnJobCode(), null);
    entity.setDependsOnJobCode(validatedScheduleDependency);
    entity.setVersion(1);
    entity.setEnabled(request.getEnabled() == null || request.getEnabled());
    requirePublishedJobReferences(resolvedTenant, entity, request.getNodes());
    definitionMapper.insert(entity);

    writeSupport.upsertNodesAndEdges(resolvedTenant, entity.getId(), request);
    appendCreatedVersionSnapshot(resolvedTenant, entity.getId());
    cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, request.getWorkflowCode());
    publishRefresh(resolvedTenant, "workflow-definition-created");

    return loadDetail(resolvedTenant, entity.getId());
  }

  /** v1 在同一创建事务内与节点边一起保存，后续覆盖定义时仍能解析原版本。 */
  private void appendCreatedVersionSnapshot(String tenantId, Long definitionId) {
    WorkflowDefinitionEntity stored = definitionMapper.selectById(tenantId, definitionId);
    WorkflowDefinitionEntity definition =
        Guard.requireFound(stored, ERR_WORKFLOW_NOT_FOUND + definitionId);
    WorkflowDefinitionSaveRequest snapshot = new WorkflowDefinitionSaveRequest();
    snapshot.setTenantId(tenantId);
    snapshot.setWorkflowCode(definition.getWorkflowCode());
    snapshot.setWorkflowName(definition.getWorkflowName());
    snapshot.setWorkflowType(definition.getWorkflowType());
    snapshot.setEnabled(definition.getEnabled());
    snapshot.setScheduleType(definition.getScheduleType());
    snapshot.setScheduleExpr(definition.getScheduleExpr());
    snapshot.setTimezone(definition.getTimezone());
    snapshot.setTriggerMode(definition.getTriggerMode());
    snapshot.setCalendarCode(definition.getCalendarCode());
    snapshot.setDependsOnJobCode(definition.getDependsOnJobCode());
    WorkflowVersionSnapshotCommand command = WorkflowVersionSnapshotCommand.builder()
        .tenantId(tenantId)
        .definitionId(definitionId)
        .workflowCode(definition.getWorkflowCode())
        .version(definition.getVersion())
        .body(snapshot)
        .scheduleEnabled(definition.getScheduleEnabled())
        .build();
    writeSupport.appendVersionSnapshot(command);
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse fullUpdate(
      Long id, WorkflowDefinitionFullUpdateRequest request, String currentUser) {
    WorkflowDefinitionSaveRequest body = request.getDefinition();
    String resolvedTenant = tenantGuard.resolveTenant(body.getTenantId());

    WorkflowDefinitionEntity loadedDefinition = definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity def =
        Guard.requireFound(loadedDefinition, ERR_WORKFLOW_NOT_FOUND + id);

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
    String previousCalendarCode = def.getCalendarCode();
    applyScheduleConfiguration(def, body, def);
    String validatedCalendarCode = validateCalendarReference(resolvedTenant, def.getCalendarCode());
    def.setCalendarCode(validatedCalendarCode);
    rejectCalendarChangeWithExpectations(
        resolvedTenant, id, previousCalendarCode, def.getCalendarCode());
    body.setCalendarCode(def.getCalendarCode());
    String dependsOnJobCode = validateScheduleDependency(
        resolvedTenant, def.getDependsOnJobCode(), def.getSingleJobDefinitionId());
    assertAndPreserveNodeRouting(resolvedTenant, id, body.getNodes());

    // BE 回退:DAG 拓扑 + 引用完整性校验(范围 = 拓扑;业务对错见 ADR-021,不在此处)
    dagValidator.validate(resolvedTenant, body);

    WorkflowDefinitionEntity effectiveDefinition = new WorkflowDefinitionEntity();
    effectiveDefinition.setEnabled(
        EmptyChecks.isNotNull(body.getEnabled()) ? body.getEnabled() : def.getEnabled());
    effectiveDefinition.setScheduleEnabled(def.getScheduleEnabled());
    requirePublishedJobReferences(resolvedTenant, effectiveDefinition, body.getNodes());

    WorkflowDefinitionVersionUpdateParam versionedUpdate =
        WorkflowDefinitionVersionUpdateParam.builder()
            .tenantId(resolvedTenant)
            .id(id)
            .expectedVersion(request.getExpectedVersion())
            .workflowName(body.getWorkflowName())
            .workflowType(body.getWorkflowType())
            .enabled(
                EmptyChecks.isNotNull(body.getEnabled()) ? body.getEnabled() : def.getEnabled())
            .scheduleEnabled(def.getScheduleEnabled())
            .scheduleType(def.getScheduleType())
            .scheduleExpr(def.getScheduleExpr())
            .timezone(def.getTimezone())
            .triggerMode(def.getTriggerMode())
            .calendarCode(def.getCalendarCode())
            .dependsOnJobCode(dependsOnJobCode)
            .description(def.getDescription())
            .build();
    int rows = definitionMapper.updateAndBumpVersion(versionedUpdate);
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
    WorkflowVersionSnapshotCommand snapshotCommand = WorkflowVersionSnapshotCommand.builder()
        .tenantId(resolvedTenant)
        .definitionId(id)
        .workflowCode(def.getWorkflowCode())
        .version(newVersion)
        .body(body)
        .scheduleEnabled(def.getScheduleEnabled())
        .savedBy(currentUser)
        .build();
    writeSupport.appendVersionSnapshot(snapshotCommand);

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
    if (Boolean.TRUE.equals(enabled)) {
      requireSavedJobReferences(resolvedTenant, id);
    }
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
    if (Boolean.TRUE.equals(scheduleEnabled)) {
      requireSavedJobReferences(resolvedTenant, id);
    }
    int rows = definitionMapper.toggleScheduleEnabled(resolvedTenant, id, scheduleEnabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.not_found", id);
    }
    WorkflowDefinitionEntity def = definitionMapper.selectById(resolvedTenant, id);
    if (EmptyChecks.isNotNull(def)) {
      cacheInvalidationService.evictWorkflowDefinition(resolvedTenant, def.getWorkflowCode());
    }
    publishRefresh(resolvedTenant, "workflow-schedule-toggled");
  }

  @Override
  @Transactional
  public WorkflowDefinitionDetailResponse updateSchedule(
      Long id, WorkflowScheduleUpdateRequest request, String currentUser) {
    String resolvedTenant = tenantGuard.resolveTenant(request.getTenantId());
    WorkflowDefinitionEntity loadedDefinition = definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity current =
        Guard.requireFound(loadedDefinition, ERR_WORKFLOW_NOT_FOUND + id);
    if (Boolean.TRUE.equals(current.getEnabled())) {
      requireSavedJobReferences(resolvedTenant, id);
    }
    String dependsOnJobCode = validateScheduleDependency(
        resolvedTenant, request.getDependsOnJobCode(), current.getSingleJobDefinitionId());
    String scheduleType = request.getScheduleType().trim();
    String triggerMode = Texts.hasText(request.getTriggerMode())
        ? request.getTriggerMode().trim()
        : defaultTriggerMode(scheduleType);
    String validatedCalendarCode =
        validateCalendarReference(resolvedTenant, request.getCalendarCode());
    request.setCalendarCode(validatedCalendarCode);
    rejectCalendarChangeWithExpectations(
        resolvedTenant, id, current.getCalendarCode(), request.getCalendarCode());
    String scheduleExpr = "CRON".equals(scheduleType) || "FIXED_RATE".equals(scheduleType)
        ? request.getScheduleExpr()
        : null;
    WorkflowScheduleUpdateParam scheduleUpdate = WorkflowScheduleUpdateParam.builder()
        .tenantId(resolvedTenant)
        .id(id)
        .expectedVersion(request.getExpectedVersion())
        .scheduleType(scheduleType)
        .scheduleExpr(scheduleExpr)
        .timezone(request.getTimezone())
        .triggerMode(triggerMode)
        .calendarCode(request.getCalendarCode())
        .dependsOnJobCode(dependsOnJobCode)
        .build();
    int rows = definitionMapper.updateScheduleConfiguration(scheduleUpdate);
    if (rows == 0) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.workflow_full_update.version_conflict",
          request.getExpectedVersion(),
          current.getVersion());
    }
    WorkflowDefinitionEntity updatedDefinitionRead =
        definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity updated =
        Guard.requireFound(updatedDefinitionRead, ERR_WORKFLOW_NOT_FOUND + id);
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
    WorkflowVersionSnapshotCommand snapshotCommand = WorkflowVersionSnapshotCommand.builder()
        .tenantId(resolvedTenant)
        .definitionId(id)
        .workflowCode(updated.getWorkflowCode())
        .version(updated.getVersion())
        .body(snapshot)
        .scheduleEnabled(updated.getScheduleEnabled())
        .savedBy(currentUser)
        .build();
    writeSupport.appendVersionSnapshot(snapshotCommand);
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
    JobDefinitionView upstream = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
    if (EmptyChecks.isNull(upstream)) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow.schedule_dependency_job_not_found", jobCode);
    }
    if (EmptyChecks.isNotNull(managedJobDefinitionId)
        && managedJobDefinitionId.equals(upstream.getId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.schedule_self_dependency");
    }
    return jobCode;
  }

  private void requireSavedJobReferences(String tenantId, Long id) {
    WorkflowDefinitionEntity definition = definitionMapper.selectById(tenantId, id);
    Guard.requireFound(definition, ERR_WORKFLOW_NOT_FOUND + id);
    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(tenantId, id, null);
    List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(nodeQuery);
    dagInspector.requireExecutableJobReferences(tenantId, nodes);
  }

  private void requirePublishedJobReferences(
      String tenantId, WorkflowDefinitionEntity definition, List<NodeItem> requestedNodes) {
    boolean workflowIsEnabled = Boolean.TRUE.equals(definition.getEnabled());
    if (!workflowIsEnabled) {
      return;
    }
    if (EmptyChecks.isEmpty(requestedNodes)) {
      return;
    }
    List<WorkflowNodeEntity> nodes = requestedNodes.stream()
        .map(item -> {
          WorkflowNodeEntity node = new WorkflowNodeEntity();
          node.setNodeCode(item.getNodeCode());
          node.setNodeType(item.getNodeType());
          node.setRelatedJobCode(item.getRelatedJobCode());
          return node;
        })
        .toList();
    dagInspector.requireExecutableJobReferences(tenantId, nodes);
  }

  private String validateCalendarReference(String tenantId, String rawCalendarCode) {
    String calendarCode = CodeNormalizer.toConfigFormOrNull(rawCalendarCode);
    if (!Texts.hasText(calendarCode)) {
      return null;
    }
    if (EmptyChecks.isNull(
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode))) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.workflow.calendar_not_found", calendarCode);
    }
    return calendarCode;
  }

  private void rejectCalendarChangeWithExpectations(
      String tenantId,
      Long workflowDefinitionId,
      String previousCalendarCode,
      String nextCalendarCode) {
    String previousNormalizedCalendarCode = CodeNormalizer.toConfigFormOrNull(previousCalendarCode);
    String nextNormalizedCalendarCode = CodeNormalizer.toConfigFormOrNull(nextCalendarCode);
    boolean calendarCodeChanged =
        !Objects.equals(previousNormalizedCalendarCode, nextNormalizedCalendarCode);
    if (calendarCodeChanged
        && definitionMapper.hasCalendarScopedExpectations(tenantId, workflowDefinitionId)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.calendar_has_expectations");
    }
  }

  @Override
  public DagValidationResult validate(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity loadedDefinition = definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity def =
        Guard.requireFound(loadedDefinition, ERR_WORKFLOW_NOT_FOUND + id);

    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(resolvedTenant, def.getId(), null);
    List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(nodeQuery);
    WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.ofDefinition(resolvedTenant, def.getId(), null);
    List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(edgeQuery);

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
    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(tenantId, id, null);
    List<WorkflowNodeEntity> nodes = nodeMapper.selectByQuery(nodeQuery);
    WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.ofDefinition(tenantId, id, null);
    List<WorkflowEdgeEntity> edges = edgeMapper.selectByQuery(edgeQuery);
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

  /** 在调用方事务中创建受管的单 Job Workflow。 */
  private void assertManagedSingleJobGraph(
      String tenantId, WorkflowDefinitionEntity definition, WorkflowDefinitionSaveRequest request) {
    if (EmptyChecks.isNull(definition.getSingleJobDefinitionId())) {
      return;
    }
    List<NodeItem> submittedNodes = request.getNodes();
    List<EdgeItem> submittedEdges = request.getEdges();
    boolean submittedGraphHasRequiredShape =
        hasRequiredManagedGraphShape(definition, request, submittedNodes, submittedEdges);
    if (!submittedGraphHasRequiredShape) {
      throw managedGraphConflict();
    }

    Map<String, NodeItem> nodesByCode = new HashMap<>();
    for (NodeItem node : submittedNodes) {
      NodeItem existingNode = nodesByCode.putIfAbsent(node.getNodeCode(), node);
      if (EmptyChecks.isNotNull(existingNode)) {
        throw managedGraphConflict();
      }
    }
    WorkflowNodeQuery nodeQuery =
        WorkflowNodeQuery.ofDefinition(tenantId, definition.getId(), null);
    List<WorkflowNodeEntity> existingNodes = nodeMapper.selectByQuery(nodeQuery);
    String jobCode = existingNodes.stream()
        .filter(node -> "JOB".equals(node.getNodeType()))
        .map(WorkflowNodeEntity::getRelatedJobCode)
        .filter(Texts::hasText)
        .findFirst()
        .orElseThrow(DefaultWorkflowDefinitionService::managedGraphConflict);
    boolean managedStartNodeMatches =
        isManagedNode(nodesByCode.get("START"), "START", "START", 0, null);
    boolean managedNodeGraphMatches = managedStartNodeMatches
        && isManagedNode(nodesByCode.get("JOB"), "JOB", "JOB", 1, jobCode)
        && isManagedNode(nodesByCode.get("END"), "END", "END", 2, null);
    if (!managedNodeGraphMatches) {
      throw managedGraphConflict();
    }
    Set<String> edges = submittedEdges.stream()
        .map(edge -> edge.getFromNodeCode() + ">" + edge.getToNodeCode() + ":"
            + (EmptyChecks.isNull(edge.getEdgeType()) ? "SUCCESS" : edge.getEdgeType()))
        .collect(Collectors.toSet());
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

  private static boolean hasRequiredManagedGraphShape(
      WorkflowDefinitionEntity definition,
      WorkflowDefinitionSaveRequest request,
      List<NodeItem> submittedNodes,
      List<EdgeItem> submittedEdges) {
    if (!"DAG".equals(definition.getWorkflowType())) {
      return false;
    }
    String requestedWorkflowType = request.getWorkflowType();
    if (EmptyChecks.isNotNull(requestedWorkflowType) && !"DAG".equals(request.getWorkflowType())) {
      return false;
    }
    if (EmptyChecks.isNull(submittedNodes)) {
      return false;
    }
    if (submittedNodes.size() != 3 || submittedNodes.stream().anyMatch(Objects::isNull)) {
      return false;
    }
    if (EmptyChecks.isNull(submittedEdges)) {
      return false;
    }
    return submittedEdges.size() == 2 && !submittedEdges.stream().anyMatch(Objects::isNull);
  }

  private static boolean isManagedNode(
      NodeItem node, String nodeCode, String nodeType, int nodeOrder, String relatedJobCode) {
    if (EmptyChecks.isNull(node)) {
      return false;
    }
    boolean nodeIdentityMatches = nodeCode.equals(node.getNodeCode())
        && nodeType.equals(node.getNodeType())
        && Objects.equals(relatedJobCode, node.getRelatedJobCode())
        && Objects.equals(nodeOrder, node.getNodeOrder());
    if (!nodeIdentityMatches) {
      return false;
    }
    boolean routingOverridesAreAbsent = !Texts.hasText(node.getWorkerGroup())
        && !Texts.hasText(node.getNodeParams())
        && !Texts.hasText(node.getCrossDayDependencies());
    if (!routingOverridesAreAbsent) {
      return false;
    }
    String retryPolicy = node.getRetryPolicy();
    boolean retryPolicyIsDefault =
        !Texts.hasText(retryPolicy) || "NONE".equals(node.getRetryPolicy());
    if (!retryPolicyIsDefault) {
      return false;
    }
    boolean retryCountIsDefault =
        EmptyChecks.isNull(node.getRetryMaxCount()) || node.getRetryMaxCount() == 0;
    if (!retryCountIsDefault) {
      return false;
    }
    boolean timeoutIsDefault =
        EmptyChecks.isNull(node.getTimeoutSeconds()) || node.getTimeoutSeconds() == 0;
    if (!timeoutIsDefault) {
      return false;
    }
    return !Boolean.FALSE.equals(node.getEnabled());
  }

  private static BizException managedGraphConflict() {
    return BizException.of(ResultCode.CONFLICT, "error.workflow.single_job_graph_managed");
  }

  /** 受管单作业 Workflow 可以修改元数据，但系统图不可变。 */
  private static void applyScheduleConfiguration(
      WorkflowDefinitionEntity target,
      WorkflowDefinitionSaveRequest request,
      WorkflowDefinitionEntity existing) {
    String requestedType = request.getScheduleType();
    boolean shouldInheritExistingScheduleConfiguration =
        !Texts.hasText(requestedType) && EmptyChecks.isNotNull(existing);
    if (shouldInheritExistingScheduleConfiguration) {
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
      boolean existingScheduleCanSupplyTriggerMode =
          EmptyChecks.isNotNull(existing) && scheduleType.equals(existing.getScheduleType());
      triggerMode = existingScheduleCanSupplyTriggerMode
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
    WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(tenantId, definitionId, null);
    List<WorkflowNodeEntity> existingNodes = nodeMapper.selectByQuery(nodeQuery);
    for (WorkflowNodeEntity node : existingNodes) {
      existingGroups.put(node.getNodeCode(), node.getWorkerGroup());
    }
    boolean existingRoutingIsConfigured = existingGroups.values().stream().anyMatch(Texts::hasText);
    boolean hasRouting = existingRoutingIsConfigured;
    if (!hasRouting) {
      hasRouting = nodes.stream().anyMatch(node -> Texts.hasText(node.getWorkerGroup()));
    }
    boolean shouldPreserveRouting =
        hasRouting && resourceAssignmentGuard.shouldPreserveExistingRouting();
    if (!shouldPreserveRouting) {
      return;
    }
    for (WorkflowDefinitionSaveRequest.NodeItem node : nodes) {
      String existingGroup = existingGroups.get(node.getNodeCode());
      resourceAssignmentGuard.assertWorkerGroupUpdateAllowed(node.getWorkerGroup(), existingGroup);
      boolean shouldInheritExistingWorkerGroup =
          EmptyChecks.isNull(node.getWorkerGroup()) && EmptyChecks.isNotNull(existingGroup);
      if (shouldInheritExistingWorkerGroup) {
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
    WorkflowDefinitionEntity loadedDefinition = definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity def =
        Guard.requireFound(loadedDefinition, ERR_WORKFLOW_NOT_FOUND + id);

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
        .map(r -> {
          Integer version = r.getVersion();
          String savedBy = r.getSavedBy();
          Instant savedAt = r.getSavedAt();
          String summary = r.getSummary();
          Integer versionForCurrentCheck = r.getVersion();
          boolean current = versionForCurrentCheck.equals(currentVersion);
          return new WorkflowDefinitionVersionSummaryResponse(
              version, savedBy, savedAt, summary, current);
        })
        .toList();
  }

  @Override
  public WorkflowDefinitionDetailResponse getVersion(Long id, String tenantId, Integer version) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    WorkflowDefinitionEntity loadedDefinition = definitionMapper.selectById(resolvedTenant, id);
    WorkflowDefinitionEntity def =
        Guard.requireFound(loadedDefinition, ERR_WORKFLOW_NOT_FOUND + id);
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
