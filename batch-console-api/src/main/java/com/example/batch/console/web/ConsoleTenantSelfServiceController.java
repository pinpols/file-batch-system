package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleQuotaPolicyApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleSystemParameterService;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
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

  /** 查看当前租户配额策略。 */
  @GetMapping("/quota")
  public CommonResponse<PageResponse<Map<String, Object>>> quota(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(quotaPolicyService.list(tenantId, null, null, 1, 100));
  }

  /** 查看租户配额用量摘要（从系统参数读取运行时统计）。 */
  @GetMapping("/usage")
  public CommonResponse<Map<String, String>> usage(@RequestParam("tenantId") String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, String> usageMap = new LinkedHashMap<>();
    parameterService
        .getValue(resolved, "tenant.usage.running-jobs")
        .ifPresent(v -> usageMap.put("runningJobs", v));
    parameterService
        .getValue(resolved, "tenant.usage.daily-triggers")
        .ifPresent(v -> usageMap.put("dailyTriggers", v));
    parameterService
        .getValue(resolved, "tenant.usage.file-count")
        .ifPresent(v -> usageMap.put("fileCount", v));
    return responseFactory.success(usageMap);
  }

  /** 提交配额扩容申请（记录为系统参数，等待管理员审批）。 */
  @PostMapping("/quota/request")
  public CommonResponse<String> requestQuotaChange(
      @RequestHeader(value = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      @RequestParam("tenantId") String tenantId,
      @Valid @RequestBody QuotaChangeRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    String requestKey = "tenant.quota-request." + System.currentTimeMillis();
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
