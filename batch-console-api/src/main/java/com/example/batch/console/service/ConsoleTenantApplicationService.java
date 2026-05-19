package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ops.ConsoleTriggerProxyService;
import com.example.batch.console.domain.param.TenantUpsertParam;
import com.example.batch.console.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.mapper.FilePipelineMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.TenantMapper;
import com.example.batch.console.mapper.WorkflowRunMapper;
import com.example.batch.console.support.auth.ConsolePasswordHasher;
import com.example.batch.console.support.auth.ConsoleRoles;
import com.example.batch.console.web.ConsoleTenantConfigCopyService;
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
  private final com.example.batch.common.config.BatchSecurityProperties securityProperties;

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

  public PageResponse<ConsoleTenantResponse> listTenants(
      String keyword, String status, PageRequest pageRequest) {
    List<Map<String, Object>> rows = tenantMapper.selectByQuery(keyword, status, pageRequest);
    long total = tenantMapper.countByQuery(keyword, status);
    List<ConsoleTenantResponse> items = rows.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  public ConsoleTenantResponse getTenant(String tenantId) {
    return toResponse(
        Guard.requireFound(
            tenantMapper.selectByTenantId(tenantId), "tenant not found: " + tenantId));
  }

  @Transactional
  public ConsoleTenantResponse createTenant(CreateTenantCommand cmd) {
    // 命名规范守卫:非 bypass(生产 / staging)拒绝保留前缀(e2e- / qa- / dev- / system 等)
    com.example.batch.console.support.naming.ReservedPrefixGuard.checkTenantId(
        cmd.tenantId(), !securityProperties.isBypassMode());
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
    // 批量创建也走守卫,任一不合规整批拒绝(strict 模式由 bypassMode 判定)
    boolean strict = !securityProperties.isBypassMode();
    for (TenantSpec spec : cmd.tenants()) {
      com.example.batch.console.support.naming.ReservedPrefixGuard.checkTenantId(
          spec.tenantId(), strict);
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
    userAccountMapper.insert(
        tenantId, username, tenantName, passwordHash, ConsoleRoles.TENANT_USER, operator);
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
