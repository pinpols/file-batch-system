package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleSystemParameterService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleTenantUsageSummaryResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import io.github.pinpols.batch.console.web.response.config.QuotaPolicyResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleTenantSelfServiceController {

  private final ConsoleQuotaPolicyApplicationService quotaPolicyService;
  private final ConsoleSystemParameterService parameterService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;
  private final BatchDateTimeSupport dateTimeSupport;

  /** 查看当前租户配额策略。 */
  @GetMapping("/quota")
  public CommonResponse<PageResponse<QuotaPolicyResponse>> quota(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(quotaPolicyService.list(tenantId, null, null, 1, 100));
  }

  /** 查看租户配额用量摘要（从系统参数读取运行时统计）。 */
  @GetMapping("/usage")
  public CommonResponse<ConsoleTenantUsageSummaryResponse> usage(
      @RequestParam("tenantId") String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    // 历史 wire：系统参数缺失时对应键不出现（record 用 @JsonInclude(NON_NULL) 省略 null 保持一致）。
    return responseFactory.success(
        new ConsoleTenantUsageSummaryResponse(
            parameterService.getValue(resolved, "tenant.usage.running-jobs").orElse(null),
            parameterService.getValue(resolved, "tenant.usage.daily-triggers").orElse(null),
            parameterService.getValue(resolved, "tenant.usage.file-count").orElse(null)));
  }

  /** 提交配额扩容申请（记录为系统参数，等待管理员审批）。 */
  @PostMapping("/quota/request")
  public CommonResponse<String> requestQuotaChange(
      @RequestHeader(value = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      @RequestParam("tenantId") String tenantId,
      @Valid @RequestBody QuotaChangeRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    String requestKey = "tenant.quota-request." + dateTimeSupport.currentEpochMillis();
    String requestValue =
        String.format(
            "{\"field\":\"%s\",\"requestedValue\":%d,\"reason\":\"%s\",\"operator\":\"%s\"}",
            request.field(), request.requestedValue(), request.reason(), operator);
    parameterService.upsert(
        tenantId, requestKey, requestValue, "Quota change request from " + operator, operator);
    return responseFactory.success(requestKey);
  }

  record QuotaChangeRequest(
      @NotBlank @Size(max = 64) String field,
      @Min(1) int requestedValue,
      @Size(max = 512) String reason) {}
}
