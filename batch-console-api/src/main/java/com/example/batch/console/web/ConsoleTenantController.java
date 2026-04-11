package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleTenantApplicationService;
import com.example.batch.console.service.ConsoleTenantApplicationService.BatchCreateTenantCommand;
import com.example.batch.console.service.ConsoleTenantApplicationService.CreateTenantCommand;
import com.example.batch.console.service.ConsoleTenantApplicationService.TenantSpec;
import com.example.batch.console.support.ConsolePrincipal;
import com.example.batch.console.web.response.ConsoleTenantResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
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

import java.util.List;

/**
 * 租户管理 REST 端点。
 *
 * <p>租户是系统的基本隔离单元，需先在此创建租户，再通过 tenant-init API 推送配置。
 */
@RestController
@Validated
@RequestMapping("/api/console/tenants")
@RequiredArgsConstructor
public class ConsoleTenantController {

    private final ConsoleTenantApplicationService tenantService;
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
                tenantService.createTenant(new CreateTenantCommand(
                        request.getTenantId(),
                        request.getTenantName(),
                        request.getDescription(),
                        request.getUsername(),
                        request.getPassword(),
                        resolveOperator(authentication))));
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<List<ConsoleTenantResponse>> batchCreate(
            @Validated @RequestBody BatchCreateTenantRequest request,
            Authentication authentication) {
        List<TenantSpec> specs = request.getTenants().stream()
                .map(s -> new TenantSpec(s.getTenantId(), s.getTenantName(), s.getDescription()))
                .toList();
        return responseFactory.success(
                tenantService.batchCreateTenants(new BatchCreateTenantCommand(
                        specs,
                        request.getUsernamePrefix(),
                        request.getPassword(),
                        resolveOperator(authentication))));
    }

    @PutMapping("/{tenantId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleTenantResponse> update(
            @PathVariable String tenantId, @Validated @RequestBody UpdateTenantRequest request) {
        return responseFactory.success(
                tenantService.updateTenant(
                        tenantId, request.getTenantName(), request.getDescription()));
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

    // ── internal ──

    private String resolveOperator(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof ConsolePrincipal p) {
            return p.username();
        }
        return authentication != null ? authentication.getName() : "system";
    }

    @Data
    public static class CreateTenantRequest {
        @NotBlank
        @Size(min = 2, max = 64)
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$",
                message = "tenant_id must be lowercase alphanumeric with hyphens, e.g. my-tenant")
        private String tenantId;

        @NotBlank
        @Size(max = 256)
        private String tenantName;

        @Size(max = 512)
        private String description;

        @NotBlank
        @Size(min = 2, max = 128)
        @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$",
                message =
                        "username must start with alphanumeric and contain only letters, digits,"
                            + " '.', '_', '-'")
        private String username;

        @NotBlank
        @Size(min = 8, max = 256)
        private String password;
    }

    @Data
    public static class UpdateTenantRequest {
        @NotBlank
        @Size(max = 256)
        private String tenantName;

        @Size(max = 512)
        private String description;
    }

    @Data
    public static class BatchCreateTenantRequest {
        @NotEmpty
        @Size(max = 50, message = "tenants must not exceed 50")
        @Valid
        private List<TenantSpecRequest> tenants;

        /** 账号用户名前缀，最终用户名为 {prefix}{tenantId}，默认 op- */
        @Size(max = 32)
        @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$",
                message = "usernamePrefix must start with alphanumeric")
        private String usernamePrefix = "op-";

        /** 批量初始密码（高强度，≥12位），首次登录后应立即修改。 */
        @NotBlank
        @Size(min = 12, max = 256)
        private String password;
    }

    @Data
    public static class TenantSpecRequest {
        @NotBlank
        @Size(min = 2, max = 64)
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$",
                message = "tenant_id must be lowercase alphanumeric with hyphens")
        private String tenantId;

        @NotBlank
        @Size(max = 256)
        private String tenantName;

        @Size(max = 512)
        private String description;
    }
}
