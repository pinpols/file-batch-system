package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsoleTriggerProxyService;
import com.example.batch.console.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.mapper.FilePipelineMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.TenantMapper;
import com.example.batch.console.mapper.WorkflowRunMapper;
import com.example.batch.console.mapper.param.TenantUpsertParam;
import com.example.batch.console.support.ConsolePasswordHasher;
import com.example.batch.console.support.ConsoleRoles;
import com.example.batch.console.web.response.ConsoleTenantResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
  public List<ConsoleTenantResponse> batchCreateTenants(BatchCreateTenantCommand cmd) {
    String prefix = cmd.usernamePrefix();
    for (TenantSpec spec : cmd.tenants()) {
      if (tenantMapper.selectByTenantId(spec.tenantId()) != null) {
        throw BizException.of(ResultCode.CONFLICT, "error.tenant.already_exists", spec.tenantId());
      }
      String username = prefix + spec.tenantId();
      if (userAccountMapper.selectByUsername(username) != null) {
        throw BizException.of(ResultCode.CONFLICT, "error.username.already_exists", username);
      }
    }
    String passwordHash = passwordHasher.encode(cmd.plainPassword());
    List<ConsoleTenantResponse> created = new ArrayList<>();
    for (TenantSpec spec : cmd.tenants()) {
      insertTenantWithAccount(
          spec.tenantId(),
          spec.tenantName(),
          spec.description(),
          prefix + spec.tenantId(),
          passwordHash,
          cmd.operator());
      created.add(toResponse(tenantMapper.selectByTenantId(spec.tenantId())));
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
