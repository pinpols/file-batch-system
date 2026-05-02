package com.example.batch.console.infrastructure.config;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import com.example.batch.console.domain.param.TenantQuotaPolicyUpdateParam;
import com.example.batch.console.domain.param.TenantQuotaPolicyUpsertParam;
import com.example.batch.console.mapper.TenantQuotaPolicyMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.request.config.QuotaPolicySaveRequest;
import java.util.List;
import java.util.Map;
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
  public PageResponse<Map<String, Object>> list(
      String tenantId, String policyCode, Boolean enabled, int pageNo, int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = quotaPolicyMapper.countByQuery(resolved, policyCode, enabled);
    List<Map<String, Object>> items =
        quotaPolicyMapper.selectByQuery(resolved, policyCode, enabled, pageRequest);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public Map<String, Object> create(QuotaPolicySaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        quotaPolicyMapper.selectByUniqueKey(tenantId, request.getPolicyCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "policy code already exists: " + request.getPolicyCode());
    }
    TenantQuotaPolicyUpsertParam param =
        TenantQuotaPolicyUpsertParam.builder()
            .tenantId(tenantId)
            .policyCode(request.getPolicyCode())
            .maxRunningJobsPerTenant(
                request.getMaxRunningJobsPerTenant() != null
                    ? request.getMaxRunningJobsPerTenant()
                    : 0)
            .maxPartitionsPerTenant(
                request.getMaxPartitionsPerTenant() != null
                    ? request.getMaxPartitionsPerTenant()
                    : 0)
            .maxQpsPerTenant(
                request.getMaxQpsPerTenant() != null ? request.getMaxQpsPerTenant() : 0)
            .fairShareWeight(
                request.getFairShareWeight() != null ? request.getFairShareWeight() : 1)
            .enabled(request.getEnabled() != null ? request.getEnabled() : true)
            .description(request.getDescription())
            .build();
    quotaPolicyMapper.insert(param);
    cacheInvalidationService.evictQuotaPolicies(tenantId);
    return quotaPolicyMapper.selectByUniqueKey(tenantId, param.getPolicyCode());
  }

  @Override
  public Map<String, Object> update(Long id, QuotaPolicySaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(quotaPolicyMapper.selectById(tenantId, id), "quota policy not found");
    TenantQuotaPolicyUpdateParam param =
        TenantQuotaPolicyUpdateParam.builder()
            .tenantId(tenantId)
            .id(id)
            .maxRunningJobsPerTenant(
                request.getMaxRunningJobsPerTenant() != null
                    ? request.getMaxRunningJobsPerTenant()
                    : (Integer) existing.get("max_running_jobs_per_tenant"))
            .maxPartitionsPerTenant(
                request.getMaxPartitionsPerTenant() != null
                    ? request.getMaxPartitionsPerTenant()
                    : (Integer) existing.get("max_partitions_per_tenant"))
            .maxQpsPerTenant(
                request.getMaxQpsPerTenant() != null
                    ? request.getMaxQpsPerTenant()
                    : (Integer) existing.get("max_qps_per_tenant"))
            .fairShareWeight(
                request.getFairShareWeight() != null
                    ? request.getFairShareWeight()
                    : (Integer) existing.get("fair_share_weight"))
            .enabled(
                request.getEnabled() != null
                    ? request.getEnabled()
                    : (Boolean) existing.get("enabled"))
            .description(
                request.getDescription() != null
                    ? request.getDescription()
                    : (String) existing.get("description"))
            .build();
    quotaPolicyMapper.update(param);
    cacheInvalidationService.evictQuotaPolicies(tenantId);
    return quotaPolicyMapper.selectById(tenantId, id);
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
