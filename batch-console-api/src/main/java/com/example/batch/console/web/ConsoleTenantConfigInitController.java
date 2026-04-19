package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsolePrincipal;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest;
import com.example.batch.console.web.request.TenantConfigCopyRequest;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户配置批量初始化与跨租户复制 REST 端点。 */
@RestController
@Validated
@RequestMapping("/api/console/config")
@RequiredArgsConstructor
@Idempotent
public class ConsoleTenantConfigInitController {

  private final ConsoleTenantConfigInitApplicationService applicationService;
  private final ConsoleTenantConfigCopyService copyService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 批量初始化或更新多个租户的配置。
   *
   * <p>mode=SKIP_EXISTING（默认）：已存在的配置不覆盖，仅创建缺失项。 mode=UPSERT：存在则更新，不存在则创建。
   * dryRun=true：只做查询和校验，不执行写入。
   */
  @PostMapping("/tenant-init")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<TenantConfigBatchInitResponse> batchInit(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TenantConfigBatchInitRequest request,
      Authentication authentication) {
    String operator = resolveOperator(authentication);
    String batchOperationId = UUID.randomUUID().toString();
    return responseFactory.success(
        applicationService.batchInit(request, operator, batchOperationId));
  }

  /**
   * 跨租户配置复制。
   *
   * <p>从源租户读取配置，转换为 Spec 后推送到目标租户列表。
   */
  @PostMapping("/tenant-copy")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<TenantConfigBatchInitResponse> tenantCopy(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TenantConfigCopyRequest request,
      Authentication authentication) {
    String operator = resolveOperator(authentication);
    String batchOperationId = UUID.randomUUID().toString();
    return responseFactory.success(copyService.copy(request, operator, batchOperationId));
  }

  private String resolveOperator(Authentication authentication) {
    if (authentication == null) {
      return "system";
    }
    if (authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.username();
    }
    return authentication.getName();
  }
}
