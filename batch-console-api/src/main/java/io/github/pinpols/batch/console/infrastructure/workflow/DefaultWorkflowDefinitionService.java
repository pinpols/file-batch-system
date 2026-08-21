package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
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
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
import java.util.List;
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

    WorkflowDefinitionEntity existing =
        definitionMapper.selectByUniqueKey(resolvedTenant, request.getWorkflowCode(), 1);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
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

    // BE 回退:与 fullUpdate 同源校验。update 不改 workflowCode,用持久化 def 的 code 作环检测 root。
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
    writeSupport.upsertNodesAndEdges(resolvedTenant, id, request);
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
    writeSupport.upsertNodesAndEdges(resolvedTenant, id, body);

    // 同事务追加版本快照(workflow-dag-designer Polish):新 version = 旧 version + 1。
    // 版本协作者序列化当前持久化后的 entity list — 与 detail 读路径一致，
    // 避免基于 request DTO 序列化导致下游字段差异。
    Integer newVersion = def.getVersion() + 1;
    writeSupport.appendVersionSnapshot(
        resolvedTenant, id, def.getWorkflowCode(), newVersion, body, currentUser);

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
