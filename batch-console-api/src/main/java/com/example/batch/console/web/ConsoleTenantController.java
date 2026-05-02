package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleTenantApplicationService;
import com.example.batch.console.service.ConsoleTenantApplicationService.BatchCreateTenantCommand;
import com.example.batch.console.service.ConsoleTenantApplicationService.CreateTenantCommand;
import com.example.batch.console.service.ConsoleTenantApplicationService.TenantSpec;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.auth.BatchCreateTenantRequest;
import com.example.batch.console.web.request.auth.CreateTenantRequest;
import com.example.batch.console.web.request.auth.UpdateTenantRequest;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import com.example.batch.console.web.request.config.TenantConfigCopyRequest;
import com.example.batch.console.web.response.auth.BatchCreateTenantsResponse;
import com.example.batch.console.web.response.auth.ConsoleTenantResponse;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.List;
import java.util.UUID;
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
  private final ConsoleTenantConfigCopyService copyService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_CONFIG_ADMIN')")
  public CommonResponse<PageResponse<ConsoleTenantResponse>> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int pageNo,
      @RequestParam(defaultValue = "20") int pageSize) {
    return responseFactory.success(
        tenantService.listTenants(keyword, status, new PageRequest(pageNo, pageSize)));
  }

  @GetMapping("/{tenantId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_CONFIG_ADMIN')")
  public CommonResponse<ConsoleTenantResponse> get(@PathVariable String tenantId) {
    return responseFactory.success(tenantService.getTenant(tenantId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<ConsoleTenantResponse> create(
      @Validated @RequestBody CreateTenantRequest request, Authentication authentication) {
    return responseFactory.success(
        tenantService.createTenant(
            new CreateTenantCommand(
                request.getTenantId(),
                request.getTenantName(),
                request.getDescription(),
                request.getUsername(),
                request.getPassword(),
                resolveOperator(authentication))));
  }

  @PostMapping("/batch")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<BatchCreateTenantsResponse> batchCreate(
      @Validated @RequestBody BatchCreateTenantRequest request, Authentication authentication) {
    String operator = resolveOperator(authentication);
    List<TenantSpec> specs =
        request.getTenants().stream()
            .map(s -> new TenantSpec(s.getTenantId(), s.getTenantName(), s.getDescription()))
            .toList();
    List<ConsoleTenantResponse> tenants =
        tenantService.batchCreateTenants(
            new BatchCreateTenantCommand(
                specs, request.getUsernamePrefix(), request.getPassword(), operator));

    TenantConfigBatchInitResponse configInit = null;
    if (request.getInitConfigFrom() != null && !request.getInitConfigFrom().isBlank()) {
      List<String> newTenantIds = tenants.stream().map(ConsoleTenantResponse::tenantId).toList();
      TenantConfigCopyRequest copyRequest = new TenantConfigCopyRequest();
      copyRequest.setSourceTenantId(request.getInitConfigFrom());
      copyRequest.setTargetTenantIds(newTenantIds);
      copyRequest.setMode(
          request.getInitMode() != null ? request.getInitMode() : InitMode.SKIP_EXISTING);
      configInit = copyService.copy(copyRequest, operator, UUID.randomUUID().toString());
    }

    return responseFactory.success(new BatchCreateTenantsResponse(tenants, configInit));
  }

  @PutMapping("/{tenantId}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<ConsoleTenantResponse> update(
      @PathVariable String tenantId, @Validated @RequestBody UpdateTenantRequest request) {
    return responseFactory.success(
        tenantService.updateTenant(tenantId, request.getTenantName(), request.getDescription()));
  }

  @PostMapping("/{tenantId}/suspend")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<ConsoleTenantResponse> suspend(@PathVariable String tenantId) {
    return responseFactory.success(tenantService.suspendTenant(tenantId));
  }

  @PostMapping("/{tenantId}/activate")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
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
