package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.mapper.TenantMapper;
import com.example.batch.console.mapper.param.TenantUpsertParam;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsolePrincipal;
import com.example.batch.console.web.response.ConsoleTenantResponse;

import jakarta.validation.constraints.NotBlank;
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
import java.util.Map;

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

    private final TenantMapper tenantMapper;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_CONFIG_ADMIN')")
    public CommonResponse<PageResponse<ConsoleTenantResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageRequest pageRequest = new PageRequest(pageNo, pageSize);
        List<Map<String, Object>> rows = tenantMapper.selectByQuery(keyword, status, pageRequest);
        long total = tenantMapper.countByQuery(keyword, status);
        List<ConsoleTenantResponse> items = rows.stream().map(this::toResponse).toList();
        return responseFactory.success(new PageResponse<>(total, pageNo, pageSize, items));
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_CONFIG_ADMIN')")
    public CommonResponse<ConsoleTenantResponse> get(@PathVariable String tenantId) {
        Map<String, Object> row =
                Guard.requireFound(
                        tenantMapper.selectByTenantId(tenantId), "tenant not found: " + tenantId);
        return responseFactory.success(toResponse(row));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleTenantResponse> create(
            @Validated @RequestBody CreateTenantRequest request, Authentication authentication) {
        if (tenantMapper.selectByTenantId(request.getTenantId()) != null) {
            throw new BizException(
                    ResultCode.CONFLICT, "tenant already exists: " + request.getTenantId());
        }
        TenantUpsertParam param = new TenantUpsertParam();
        param.setTenantId(request.getTenantId());
        param.setTenantName(request.getTenantName());
        param.setStatus("ACTIVE");
        param.setDescription(request.getDescription());
        param.setCreatedBy(resolveOperator(authentication));
        tenantMapper.insert(param);
        return responseFactory.success(
                toResponse(tenantMapper.selectByTenantId(request.getTenantId())));
    }

    @PutMapping("/{tenantId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleTenantResponse> update(
            @PathVariable String tenantId, @Validated @RequestBody UpdateTenantRequest request) {
        assertExists(tenantId);
        tenantMapper.update(tenantId, request.getTenantName(), request.getDescription());
        return responseFactory.success(toResponse(tenantMapper.selectByTenantId(tenantId)));
    }

    @PostMapping("/{tenantId}/suspend")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleTenantResponse> suspend(@PathVariable String tenantId) {
        assertExists(tenantId);
        tenantMapper.updateStatus(tenantId, "SUSPENDED");
        return responseFactory.success(toResponse(tenantMapper.selectByTenantId(tenantId)));
    }

    @PostMapping("/{tenantId}/activate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<ConsoleTenantResponse> activate(@PathVariable String tenantId) {
        assertExists(tenantId);
        tenantMapper.updateStatus(tenantId, "ACTIVE");
        return responseFactory.success(toResponse(tenantMapper.selectByTenantId(tenantId)));
    }

    // ── internal ──

    private void assertExists(String tenantId) {
        Guard.requireFound(
                tenantMapper.selectByTenantId(tenantId), "tenant not found: " + tenantId);
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
    }

    @Data
    public static class UpdateTenantRequest {
        @NotBlank
        @Size(max = 256)
        private String tenantName;

        @Size(max = 512)
        private String description;
    }
}
