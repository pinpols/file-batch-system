package com.example.batch.console.service;

import com.example.batch.common.config.BatchProfileSupport;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.config.ConsoleTenantConfigCopyService;
import com.example.batch.console.application.ops.ConsoleTriggerProxyService;
import com.example.batch.console.domain.param.TenantUpsertParam;
import com.example.batch.console.domain.workflow.mapper.WorkflowRunMapper;
import com.example.batch.console.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.mapper.FilePipelineMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.TenantMapper;
import com.example.batch.console.support.auth.ConsolePasswordHasher;
import com.example.batch.console.support.auth.ConsoleRoles;
import com.example.batch.console.support.naming.ReservedPrefixGuard;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import com.example.batch.console.web.request.config.TenantConfigCopyRequest;
import com.example.batch.console.web.response.auth.BatchCreateTenantsResponse;
import com.example.batch.console.web.response.auth.ConsoleTenantResponse;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsoleTenantApplicationService {

  private static final List<String> ACTIVE_JOB_STATUSES =
      List.of("CREATED", "WAITING", "READY", "RUNNING", "PARTIAL_FAILED");
  private static final List<String> ACTIVE_PIPELINE_STATUSES =
      List.of("CREATED", "RUNNING", "COMPENSATING");
  private static final List<String> ACTIVE_WORKFLOW_STATUSES = List.of("CREATED", "RUNNING");

  private final TenantMapper tenantMapper;
  private final ConsoleUserAccountMapper userAccountMapper;
  private final ConsolePasswordHasher passwordHasher;
  private final JobInstanceMapper jobInstanceMapper;
  private final FilePipelineMapper filePipelineMapper;
  private final WorkflowRunMapper workflowRunMapper;
  private final ConsoleTriggerProxyService triggerProxyService;
  private final ConsoleTenantConfigCopyService configCopyService;
  // bypassMode=true(本地/E2E)时关守卫,允许 e2e-/test- 前缀;production 时拒绝
  private final org.springframework.core.env.Environment environment;

  public record CreateTenantCommand(
      String tenantId,
      String tenantName,
      String description,
      String username,
      String plainPassword,
      String operator) {}

  public record TenantSpec(String tenantId, String tenantName, String description) {}

  public record BatchCreateTenantCommand(
      List<TenantSpec> tenants, String usernamePrefix, String plainPassword, String operator) {}

  /** sourceTenantId 为 null/blank 表示不复制配置；mode 为 null 时落 SKIP_EXISTING 默认。 */
  public record ConfigInitOption(String sourceTenantId, InitMode mode) {
    public boolean enabled() {
      return sourceTenantId != null && !sourceTenantId.isBlank();
    }
  }

  /**
   * 永远从「租户切换列表」隐藏的 tenant_id:
   *
   * <ul>
   *   <li>{@code default} — V55 seed 的「配置模板」库,新租户初始化时复制 queue/window/calendar/template,**不是业务租户**。
   *   <li>{@code default-tenant} — V42 演示账号 + V50 自动回填的孤儿;V51 已删账号, V148 在无引用时自动清表,残留场景作为防御性兜底。
   * </ul>
   *
   * <p>注:{@code system} 保留(管理员需要切到此租户管理跨租户配置),由 FE 按角色决定是否展示。
   */
  private static final Set<String> HIDDEN_TENANT_IDS = Set.of("default", "default-tenant");

  public PageResponse<ConsoleTenantResponse> listTenants(
      String keyword, String status, PageRequest pageRequest) {
    List<Map<String, Object>> rows = tenantMapper.selectByQuery(keyword, status, pageRequest);
    long total = tenantMapper.countByQuery(keyword, status);
    List<ConsoleTenantResponse> items =
        rows.stream()
            .map(this::toResponse)
            .filter(t -> !HIDDEN_TENANT_IDS.contains(t.tenantId()))
            .toList();
    // total 不精确扣 1:filter 后量级影响 ≤ HIDDEN_TENANT_IDS.size(),分页显示可接受,
    // 避免再发一次 count 查询。
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  public ConsoleTenantResponse getTenant(String tenantId) {
    return toResponse(
        Guard.requireFound(
            tenantMapper.selectByTenantId(tenantId), "tenant not found: " + tenantId));
  }

  @Transactional
  public ConsoleTenantResponse createTenant(CreateTenantCommand cmd) {
    // 命名规范守卫双模式(2026-05-21):
    //   PROD:拒 test prefix(防 test 污染生产)
    //   NON-PROD:必须 test prefix 或 DEV_FIXTURE 白名单(ta/tb/tc/default-tenant),防裸 ID 残留无主
    // 之前用 bypassMode 判定会导致 local/e2e 完全跳过校验 → td/te/tx 等残留无主,cleanup 按 prefix 清不掉
    boolean productionMode = BatchProfileSupport.isProductionProfile(environment);
    ReservedPrefixGuard.checkTenantId(cmd.tenantId(), productionMode);
    if (tenantMapper.selectByTenantId(cmd.tenantId()) != null) {
      throw BizException.of(ResultCode.CONFLICT, "error.tenant.already_exists", cmd.tenantId());
    }
    if (userAccountMapper.selectByUsername(cmd.username()) != null) {
      throw BizException.of(ResultCode.CONFLICT, "error.username.already_exists", cmd.username());
    }
    insertTenantWithAccount(
        cmd.tenantId(),
        cmd.tenantName(),
        cmd.description(),
        cmd.username(),
        passwordHasher.encode(cmd.plainPassword()),
        cmd.operator());
    return toResponse(tenantMapper.selectByTenantId(cmd.tenantId()));
  }

  @Transactional
  public BatchCreateTenantsResponse batchCreateTenants(
      BatchCreateTenantCommand cmd, ConfigInitOption init) {
    List<ConsoleTenantResponse> tenants = doBatchCreateTenants(cmd);
    TenantConfigBatchInitResponse configInit = null;
    if (init != null && init.enabled()) {
      TenantConfigCopyRequest copyRequest = new TenantConfigCopyRequest();
      copyRequest.setSourceTenantId(init.sourceTenantId());
      copyRequest.setTargetTenantIds(
          tenants.stream().map(ConsoleTenantResponse::tenantId).toList());
      copyRequest.setMode(init.mode() != null ? init.mode() : InitMode.SKIP_EXISTING);
      configInit =
          configCopyService.copy(copyRequest, cmd.operator(), UUID.randomUUID().toString());
    }
    return new BatchCreateTenantsResponse(tenants, configInit);
  }

  private List<ConsoleTenantResponse> doBatchCreateTenants(BatchCreateTenantCommand cmd) {
    String prefix = cmd.usernamePrefix();
    // R7-A3-P1: 预检从 N 条单查改成 1 条 IN 查询；插入后 response 也 1 条批量取，整体 SELECT 数固定 = 2 + N。
    List<String> requestedTenantIds = cmd.tenants().stream().map(TenantSpec::tenantId).toList();
    Set<String> conflictingTenantIds =
        tenantMapper.selectByTenantIds(requestedTenantIds).stream()
            .map(row -> str(row, "tenant_id"))
            .collect(Collectors.toSet());
    for (TenantSpec spec : cmd.tenants()) {
      if (conflictingTenantIds.contains(spec.tenantId())) {
        throw BizException.of(ResultCode.CONFLICT, "error.tenant.already_exists", spec.tenantId());
      }
    }
    // 批量创建也走守卫,任一不合规整批拒绝(双模式由 productionProfile 判定)
    boolean productionMode = BatchProfileSupport.isProductionProfile(environment);
    for (TenantSpec spec : cmd.tenants()) {
      ReservedPrefixGuard.checkTenantId(spec.tenantId(), productionMode);
    }
    // username 单查暂保留（ConsoleUserAccountMapper 未暴露 batch 接口；命中冲突即拒绝整批）。
    for (TenantSpec spec : cmd.tenants()) {
      String username = prefix + spec.tenantId();
      if (userAccountMapper.selectByUsername(username) != null) {
        throw BizException.of(ResultCode.CONFLICT, "error.username.already_exists", username);
      }
    }
    String passwordHash = passwordHasher.encode(cmd.plainPassword());
    for (TenantSpec spec : cmd.tenants()) {
      insertTenantWithAccount(
          spec.tenantId(),
          spec.tenantName(),
          spec.description(),
          prefix + spec.tenantId(),
          passwordHash,
          cmd.operator());
    }
    Map<String, Map<String, Object>> insertedRows =
        tenantMapper.selectByTenantIds(requestedTenantIds).stream()
            .collect(Collectors.toMap(row -> str(row, "tenant_id"), row -> row, (a, b) -> a));
    List<ConsoleTenantResponse> created = new ArrayList<>(cmd.tenants().size());
    for (TenantSpec spec : cmd.tenants()) {
      Map<String, Object> row = insertedRows.get(spec.tenantId());
      if (row == null) {
        throw BizException.of(ResultCode.NOT_FOUND, "error.tenant.not_found", spec.tenantId());
      }
      created.add(toResponse(row));
    }
    return created;
  }

  public ConsoleTenantResponse updateTenant(
      String tenantId, String tenantName, String description) {
    assertExists(tenantId);
    tenantMapper.update(tenantId, tenantName, description);
    return toResponse(tenantMapper.selectByTenantId(tenantId));
  }

  public ConsoleTenantResponse suspendTenant(String tenantId) {
    assertExists(tenantId);
    assertNoActiveInstances(tenantId);
    tenantMapper.updateStatus(tenantId, "SUSPENDED");
    triggerProxyService.pauseByTenant(tenantId);
    return toResponse(tenantMapper.selectByTenantId(tenantId));
  }

  private void assertNoActiveInstances(String tenantId) {
    long jobs = jobInstanceMapper.countByStatuses(tenantId, ACTIVE_JOB_STATUSES);
    long pipelines = filePipelineMapper.countByStatuses(tenantId, ACTIVE_PIPELINE_STATUSES);
    long workflows = workflowRunMapper.countByStatuses(tenantId, ACTIVE_WORKFLOW_STATUSES);
    long total = jobs + pipelines + workflows;
    if (total > 0) {
      throw BizException.of(
          ResultCode.BUSINESS_ERROR,
          "error.common.business_error_detail",
          "cannot suspend tenant with active instances"
              + " (jobs="
              + jobs
              + ", pipelines="
              + pipelines
              + ", workflows="
              + workflows
              + ")");
    }
  }

  public ConsoleTenantResponse activateTenant(String tenantId) {
    assertExists(tenantId);
    tenantMapper.updateStatus(tenantId, "ACTIVE");
    triggerProxyService.resumeByTenant(tenantId);
    return toResponse(tenantMapper.selectByTenantId(tenantId));
  }

  private void insertTenantWithAccount(
      String tenantId,
      String tenantName,
      String description,
      String username,
      String passwordHash,
      String operator) {
    TenantUpsertParam param = new TenantUpsertParam();
    param.setTenantId(tenantId);
    param.setTenantName(tenantName);
    param.setStatus("ACTIVE");
    param.setDescription(description);
    param.setCreatedBy(operator);
    tenantMapper.insert(param);
    // 2026-05 角色重设计:租户首账号必须是 TENANT_ADMIN,后续由它派生 TENANT_USER。
    // 若给 TENANT_USER,新租户没人能加员工,平台 ADMIN 还要补一刀,500 租户场景下不可持续。
    userAccountMapper.insert(
        tenantId, username, tenantName, passwordHash, ConsoleRoles.TENANT_ADMIN, operator);
  }

  private void assertExists(String tenantId) {
    Guard.requireFound(tenantMapper.selectByTenantId(tenantId), "tenant not found: " + tenantId);
  }

  private ConsoleTenantResponse toResponse(Map<String, Object> row) {
    return new ConsoleTenantResponse(
        row.get("id") instanceof Number n ? n.longValue() : null,
        str(row, "tenant_id"),
        str(row, "tenant_name"),
        str(row, "status"),
        str(row, "description"),
        str(row, "created_by"),
        str(row, "created_at"),
        str(row, "updated_at"));
  }

  private String str(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? null : String.valueOf(v);
  }
}
