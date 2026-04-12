package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleUserAccountService;
import com.example.batch.console.web.request.ResetPasswordRequest;
import com.example.batch.console.web.request.UpdateUserAccountRequest;
import com.example.batch.console.web.response.ConsoleUserAccountResponse;

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

/** 控制台账号管理 REST 端点（仅平台管理员）。 */
@RestController
@Validated
@RequestMapping("/api/console/users")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleUserAccountController {

    private final ConsoleUserAccountService userAccountService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping
    public CommonResponse<PageResponse<ConsoleUserAccountResponse>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return responseFactory.success(
                userAccountService.list(tenantId, keyword, new PageRequest(pageNo, pageSize)));
    }

    @GetMapping("/{id}")
    public CommonResponse<ConsoleUserAccountResponse> get(@PathVariable long id) {
        return responseFactory.success(userAccountService.get(id));
    }

    @PutMapping("/{id}")
    public CommonResponse<ConsoleUserAccountResponse> update(
            @PathVariable long id, @Validated @RequestBody UpdateUserAccountRequest request) {
        return responseFactory.success(
                userAccountService.update(id, request.getDisplayName(), request.getAuthoritiesCsv()));
    }

    @PostMapping("/{id}/reset-password")
    public CommonResponse<Void> resetPassword(
            @PathVariable long id, @Validated @RequestBody ResetPasswordRequest request) {
        userAccountService.resetPassword(id, request.getNewPassword());
        return responseFactory.success(null);
    }

    @PostMapping("/{id}/enable")
    public CommonResponse<ConsoleUserAccountResponse> enable(@PathVariable long id) {
        return responseFactory.success(userAccountService.enable(id));
    }

    @PostMapping("/{id}/disable")
    public CommonResponse<ConsoleUserAccountResponse> disable(@PathVariable long id) {
        return responseFactory.success(userAccountService.disable(id));
    }
}
