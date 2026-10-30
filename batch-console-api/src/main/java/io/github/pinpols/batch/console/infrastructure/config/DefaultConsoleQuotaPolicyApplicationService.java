package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.QuotaPolicySaveRequest;
import io.github.pinpols.batch.console.application.contract.response.config.QuotaPolicyResponse;
import io.github.pinpols.batch.console.domain.param.TenantQuotaPolicyUpdateParam;
import io.github.pinpols.batch.console.domain.param.TenantQuotaPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.rbac.view.TenantQuotaPolicyRow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleQuotaPolicyApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleQuotaPolicyApplicationService
    implements ConsoleQuotaPolicyApplicationService {

  private final TenantQuotaPolicyMapper quotaPolicyMapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Override
  public PageResponse<QuotaPolicyResponse> list(
      String tenantId, String policyCode, Boolean enabled, int pageNo, int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = quotaPolicyMapper.countByQuery(resolved, policyCode, enabled);
    List<QuotaPolicyResponse> items =
        quotaPolicyMapper.selectByQuery(resolved, policyCode, enabled, pageRequest).stream()
            .map(QuotaPolicyResponse::from)
            .toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public QuotaPolicyResponse create(QuotaPolicySaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantQuotaPolicyRow existing =
        quotaPolicyMapper.selectByUniqueKey(tenantId, request.getPolicyCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "policy code already exists: " + request.getPolicyCode());
    }
    TenantQuotaPolicyUpsertParam param = TenantQuotaPolicyUpsertParam.builder()
        .tenantId(tenantId)
        .policyCode(request.getPolicyCode())
        .maxRunningJobsPerTenant(
            request.getMaxRunningJobsPerTenant() != null ? request.getMaxRunningJobsPerTenant() : 0)
        .maxPartitionsPerTenant(
            request.getMaxPartitionsPerTenant() != null ? request.getMaxPartitionsPerTenant() : 0)
        .maxQpsPerTenant(request.getMaxQpsPerTenant() != null ? request.getMaxQpsPerTenant() : 0)
        .fairShareWeight(request.getFairShareWeight() != null ? request.getFairShareWeight() : 1)
        .enabled(request.getEnabled() == null || request.getEnabled())
        .description(request.getDescription())
        .build();
    quotaPolicyMapper.insert(param);
    cacheInvalidationService.evictQuotaPolicies(tenantId);
    TenantQuotaPolicyRow createdPolicy =
        quotaPolicyMapper.selectByUniqueKey(tenantId, param.getPolicyCode());
    return QuotaPolicyResponse.from(createdPolicy);
  }

  @Override
  public QuotaPolicyResponse update(Long id, QuotaPolicySaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    TenantQuotaPolicyRow selectedPolicy = quotaPolicyMapper.selectById(tenantId, id);
    TenantQuotaPolicyRow existing = Guard.requireFound(selectedPolicy, "quota policy not found");
    TenantQuotaPolicyUpdateParam param = TenantQuotaPolicyUpdateParam.builder()
        .tenantId(tenantId)
        .id(id)
        .maxRunningJobsPerTenant(
            request.getMaxRunningJobsPerTenant() != null
                ? request.getMaxRunningJobsPerTenant()
                : existing.maxRunningJobsPerTenant())
        .maxPartitionsPerTenant(
            request.getMaxPartitionsPerTenant() != null
                ? request.getMaxPartitionsPerTenant()
                : existing.maxPartitionsPerTenant())
        .maxQpsPerTenant(
            request.getMaxQpsPerTenant() != null
                ? request.getMaxQpsPerTenant()
                : existing.maxQpsPerTenant())
        .fairShareWeight(
            request.getFairShareWeight() != null
                ? request.getFairShareWeight()
                : existing.fairShareWeight())
        .enabled(
            EmptyChecks.isNotNull(request.getEnabled()) ? request.getEnabled() : existing.enabled())
        .description(
            EmptyChecks.isNotNull(request.getDescription())
                ? request.getDescription()
                : existing.description())
        .build();
    quotaPolicyMapper.update(param);
    cacheInvalidationService.evictQuotaPolicies(tenantId);
    TenantQuotaPolicyRow updatedPolicy = quotaPolicyMapper.selectById(tenantId, id);
    return QuotaPolicyResponse.from(updatedPolicy);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = quotaPolicyMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.quota.policy_not_found");
    }
    cacheInvalidationService.evictQuotaPolicies(resolved);
  }
}
