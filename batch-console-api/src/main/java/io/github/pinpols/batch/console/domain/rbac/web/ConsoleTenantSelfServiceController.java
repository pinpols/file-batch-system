package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.response.config.QuotaPolicyResponse;
import io.github.pinpols.batch.console.domain.rbac.application.ConsoleTenantSelfServiceApplicationService;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.QuotaChangeRequest;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleTenantUsageSummaryResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租户自助服务：查看配额与用量、提交配额变更申请。 */
@RestController
@Validated
@RequestMapping("/api/console/tenants")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN_OR_TENANT_USER)
@RequiredArgsConstructor
@Idempotent
public class ConsoleTenantSelfServiceController {

  private final ConsoleTenantSelfServiceApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 查看当前租户配额策略。 */
  @GetMapping("/quota")
  public CommonResponse<PageResponse<QuotaPolicyResponse>> quota(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.quota(tenantId));
  }

  /** 查看租户配额用量摘要（从系统参数读取运行时统计）。 */
  @GetMapping("/usage")
  public CommonResponse<ConsoleTenantUsageSummaryResponse> usage(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.usage(tenantId));
  }

  /** 提交配额扩容申请（记录为系统参数，等待管理员审批）。 */
  @PostMapping("/quota/request")
  public CommonResponse<String> requestQuotaChange(
      @RequestHeader(value = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      @RequestParam("tenantId") String tenantId,
      @Valid @RequestBody QuotaChangeRequest request) {
    String requestKey = applicationService.requestQuotaChange(tenantId, request);
    return responseFactory.success(requestKey);
  }
}
