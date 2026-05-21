package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleUserAccountService;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.auth.CreateUserAccountRequest;
import com.example.batch.console.web.request.auth.ResetPasswordRequest;
import com.example.batch.console.web.request.auth.UpdateUserAccountRequest;
import com.example.batch.console.web.response.auth.ConsoleUserAccountResponse;
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

/**
 * 控制台账号管理 REST 端点。
 *
 * <p>2026-05 角色重设计:类级粗筛允许 ADMIN + TENANT_ADMIN; {@link
 * com.example.batch.console.service.ConsoleUserAccountService} 在 Service 层强制注入 tenantId / 拒绝越权 /
 * 拒绝授 ADMIN-AUDITOR 等管控,粗筛只是把无关角色挡在门外。
 */
@RestController
@Validated
@RequestMapping("/api/console/users")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
@Idempotent
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

  @PostMapping
  public CommonResponse<ConsoleUserAccountResponse> create(
      @Validated @RequestBody CreateUserAccountRequest request) {
    return responseFactory.success(
        userAccountService.create(
            request.getTenantId(),
            request.getUsername(),
            request.getPassword(),
            request.getDisplayName(),
            request.getAuthoritiesCsv()));
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
