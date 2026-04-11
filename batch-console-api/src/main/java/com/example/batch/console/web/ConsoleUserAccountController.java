package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsolePasswordHasher;
import com.example.batch.console.support.ConsoleRoles;
import com.example.batch.console.support.ConsoleSessionRegistry;
import com.example.batch.console.web.response.ConsoleUserAccountResponse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
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

/** 控制台账号管理 REST 端点（仅平台管理员）。 */
@RestController
@Validated
@RequestMapping("/api/console/users")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleUserAccountController {

    private final ConsoleUserAccountMapper userAccountMapper;
    private final ConsolePasswordHasher passwordHasher;
    private final ConsoleSessionRegistry sessionRegistry;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping
    public CommonResponse<PageResponse<ConsoleUserAccountResponse>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageRequest pageRequest = new PageRequest(pageNo, pageSize);
        List<Map<String, Object>> rows =
                userAccountMapper.selectByQuery(tenantId, keyword, pageRequest);
        long total = userAccountMapper.countByQuery(tenantId, keyword);
        List<ConsoleUserAccountResponse> items = rows.stream().map(this::toResponse).toList();
        return responseFactory.success(new PageResponse<>(total, pageNo, pageSize, items));
    }

    @GetMapping("/{id}")
    public CommonResponse<ConsoleUserAccountResponse> get(@PathVariable long id) {
        return responseFactory.success(toResponse(assertExists(id)));
    }

    @PutMapping("/{id}")
    public CommonResponse<ConsoleUserAccountResponse> update(
            @PathVariable long id, @Validated @RequestBody UpdateUserRequest request) {
        assertExists(id);
        String authoritiesCsv = normalizeAuthorities(request.getAuthoritiesCsv());
        userAccountMapper.updateProfile(id, request.getDisplayName(), authoritiesCsv);
        return responseFactory.success(toResponse(userAccountMapper.selectById(id)));
    }

    @PostMapping("/{id}/reset-password")
    public CommonResponse<Void> resetPassword(
            @PathVariable long id, @Validated @RequestBody ResetPasswordRequest request) {
        Map<String, Object> account = assertExists(id);
        userAccountMapper.updatePasswordHash(id, passwordHasher.encode(request.getNewPassword()));
        sessionRegistry.invalidateSession(str(account, "username"), str(account, "tenant_id"));
        return responseFactory.success(null);
    }

    @PostMapping("/{id}/enable")
    public CommonResponse<ConsoleUserAccountResponse> enable(@PathVariable long id) {
        assertExists(id);
        userAccountMapper.updateEnabled(id, true);
        return responseFactory.success(toResponse(userAccountMapper.selectById(id)));
    }

    @PostMapping("/{id}/disable")
    public CommonResponse<ConsoleUserAccountResponse> disable(@PathVariable long id) {
        Map<String, Object> account = assertExists(id);
        userAccountMapper.updateEnabled(id, false);
        sessionRegistry.invalidateSession(str(account, "username"), str(account, "tenant_id"));
        return responseFactory.success(toResponse(userAccountMapper.selectById(id)));
    }

    // ── internal ──

    private Map<String, Object> assertExists(long id) {
        return Guard.requireFound(
                userAccountMapper.selectById(id), "user account not found: " + id);
    }

    private ConsoleUserAccountResponse toResponse(Map<String, Object> row) {
        return new ConsoleUserAccountResponse(
                row.get("id") instanceof Number n ? n.longValue() : null,
                str(row, "tenant_id"),
                str(row, "username"),
                str(row, "display_name"),
                str(row, "authorities_csv"),
                Boolean.TRUE.equals(row.get("enabled")),
                str(row, "created_at"),
                str(row, "updated_at"));
    }

    private String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String normalizeAuthorities(String raw) {
        if (raw == null || raw.isBlank()) {
            return ConsoleRoles.USER;
        }
        return raw.trim().toUpperCase();
    }

    @Data
    public static class UpdateUserRequest {
        @Size(max = 256)
        private String displayName;

        @Size(max = 512)
        private String authoritiesCsv;
    }

    @Data
    public static class ResetPasswordRequest {
        @NotBlank
        @Size(min = 8, max = 256)
        private String newPassword;
    }
}
