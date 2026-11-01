package io.github.pinpols.batch.console.domain.rbac.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.application.contract.response.config.QuotaPolicyResponse;
import io.github.pinpols.batch.console.application.observability.ConsoleSystemParameterService;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.QuotaChangeRequest;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleTenantUsageSummaryResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 租户配额查询、用量汇总与扩容申请。 */
@Service
@RequiredArgsConstructor
public class ConsoleTenantSelfServiceApplicationService {
  private final ConsoleQuotaPolicyApplicationService quotaPolicyService;
  private final ConsoleSystemParameterService parameterService;
  private final ConsoleTenantGuard tenantGuard;
  private final BatchDateTimeSupport dateTimeSupport;
  private final ConsoleRequestMetadataResolver metadataResolver;

  public PageResponse<QuotaPolicyResponse> quota(String tenantId) {
    return quotaPolicyService.listAll(tenantId, 1, 100);
  }

  public ConsoleTenantUsageSummaryResponse usage(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String runningJobs =
        parameterService.getValue(resolved, "tenant.usage.running-jobs").orElse(null);
    String dailyTriggers =
        parameterService.getValue(resolved, "tenant.usage.daily-triggers").orElse(null);
    String fileCount =
        parameterService.getValue(resolved, "tenant.usage.file-count").orElse(null);
    return new ConsoleTenantUsageSummaryResponse(runningJobs, dailyTriggers, fileCount);
  }

  public String requestQuotaChange(String tenantId, QuotaChangeRequest request) {
    String operator = metadataResolver.current().operatorId();
    String requestKey = "tenant.quota-request." + dateTimeSupport.currentEpochMillis();
    QuotaRequestPayload payload = new QuotaRequestPayload(
        request.field(),
        request.requestedValue(),
        String.valueOf(request.reason()),
        String.valueOf(operator));
    parameterService.upsert(
        tenantId,
        requestKey,
        JsonUtils.toJson(payload),
        "Quota change request from " + operator,
        operator);
    return requestKey;
  }

  private record QuotaRequestPayload(
      String field, int requestedValue, String reason, String operator) {}
}
