package com.example.batch.console.domain.rbac.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.domain.rbac.service.ConsoleTenantApplicationService;
import com.example.batch.console.domain.rbac.service.ConsoleTenantApplicationService.BatchCreateTenantCommand;
import com.example.batch.console.domain.rbac.service.ConsoleTenantApplicationService.ConfigInitOption;
import com.example.batch.console.domain.rbac.service.ConsoleTenantApplicationService.CreateTenantCommand;
import com.example.batch.console.domain.rbac.service.ConsoleTenantApplicationService.TenantSpec;
import com.example.batch.console.domain.rbac.service.ConsoleTenantReadinessService;
import com.example.batch.console.domain.rbac.support.ConsolePrincipal;
import com.example.batch.console.domain.rbac.web.request.BatchCreateTenantRequest;
import com.example.batch.console.domain.rbac.web.request.CreateTenantRequest;
import com.example.batch.console.domain.rbac.web.request.UpdateTenantRequest;
import com.example.batch.console.domain.rbac.web.response.BatchCreateTenantsResponse;
import com.example.batch.console.domain.rbac.web.response.ConsoleTenantResponse;
import com.example.batch.console.domain.rbac.web.response.ProvisionTenantResponse;
import com.example.batch.console.domain.rbac.web.response.TenantReadinessResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理 REST 端点。
 *
 * <p>租户是系统的基本隔离单元，需先在此创建租户，再通过 tenant-init API 推送配置。
 */
@RestController
@Validated
@RequestMapping("/api/console/tenants")
@RequiredArgsConstructor
@Idempotent
public class ConsoleTenantController {

  private final ConsoleTenantApplicationService tenantService;
  private final ConsoleTenantReadinessService readinessService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN')")
  public CommonResponse<PageResponse<ConsoleTenantResponse>> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int pageNo,
      @RequestParam(defaultValue = "20") int pageSize) {
    return responseFactory.success(
        tenantService.listTenants(keyword, status, new PageRequest(pageNo, pageSize)));
  }

  @GetMapping("/{tenantId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN')")
  public CommonResponse<ConsoleTenantResponse> get(@PathVariable String tenantId) {
    return responseFactory.success(tenantService.getTenant(tenantId));
  }

  /**
   * 租户就绪自检(只读):扫该租户 template / channel / queue / job 配置闭环,返回 blocking / warning 清单。
   *
   * <p>ADR-026 dry-run 边界:本端点只看「配置完整性 / 会不会跑」(模板关键字段空、渠道凭据缺、queue_code 悬空),
   * **不看**业务结果对不对(不执行取数、不比对数据),落在 ADR-026 √ 一侧。
   */
  @GetMapping("/{tenantId}/readiness")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_TENANT_ADMIN')")
  public CommonResponse<TenantReadinessResponse> readiness(@PathVariable String tenantId) {
    return responseFactory.success(readinessService.check(tenantId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "tenant.create",
      aggregateType = "tenant",
      aggregateId = "#request.tenantId",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<ProvisionTenantResponse> create(
      @Validated @RequestBody CreateTenantRequest request, Authentication authentication) {
    return responseFactory.success(
        tenantService.provisionTenant(
            new CreateTenantCommand(
                request.getTenantId(),
                request.getTenantName(),
                request.getDescription(),
                request.getUsername(),
                request.getPassword(),
                resolveOperator(authentication)),
            new ConfigInitOption(request.getInitConfigFrom(), request.getInitMode())));
  }

  @PostMapping("/batch")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(action = "tenant.batchCreate", aggregateType = "tenant", recordParams = false)
  public CommonResponse<BatchCreateTenantsResponse> batchCreate(
      @Validated @RequestBody BatchCreateTenantRequest request, Authentication authentication) {
    String operator = resolveOperator(authentication);
    List<TenantSpec> specs =
        request.getTenants().stream()
            .map(s -> new TenantSpec(s.getTenantId(), s.getTenantName(), s.getDescription()))
            .toList();
    return responseFactory.success(
        tenantService.batchCreateTenants(
            new BatchCreateTenantCommand(
                specs, request.getUsernamePrefix(), request.getPassword(), operator),
            new ConfigInitOption(request.getInitConfigFrom(), request.getInitMode())));
  }

  @PutMapping("/{tenantId}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "tenant.update",
      aggregateType = "tenant",
      aggregateId = "#tenantId",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleTenantResponse> update(
      @PathVariable String tenantId, @Validated @RequestBody UpdateTenantRequest request) {
    return responseFactory.success(
        tenantService.updateTenant(tenantId, request.getTenantName(), request.getDescription()));
  }

  @PostMapping("/{tenantId}/suspend")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "tenant.suspend",
      aggregateType = "tenant",
      aggregateId = "#tenantId",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleTenantResponse> suspend(@PathVariable String tenantId) {
    return responseFactory.success(tenantService.suspendTenant(tenantId));
  }

  @PostMapping("/{tenantId}/activate")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "tenant.activate",
      aggregateType = "tenant",
      aggregateId = "#tenantId",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleTenantResponse> activate(@PathVariable String tenantId) {
    return responseFactory.success(tenantService.activateTenant(tenantId));
  }

  private String resolveOperator(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof ConsolePrincipal p) {
      return p.username();
    }
    return authentication != null ? authentication.getName() : "system";
  }
}
