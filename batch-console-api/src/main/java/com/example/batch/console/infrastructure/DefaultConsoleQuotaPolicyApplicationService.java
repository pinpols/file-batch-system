package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsoleQuotaPolicyApplicationService;
import com.example.batch.console.mapper.TenantQuotaPolicyMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.QuotaPolicySaveRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            throw new BizException(
                    ResultCode.CONFLICT, "policy code already exists: " + request.getPolicyCode());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("tenant_id", tenantId);
        params.put("policy_code", request.getPolicyCode());
        params.put(
                "max_running_jobs_per_tenant",
                request.getMaxRunningJobsPerTenant() != null
                        ? request.getMaxRunningJobsPerTenant()
                        : 0);
        params.put(
                "max_partitions_per_tenant",
                request.getMaxPartitionsPerTenant() != null
                        ? request.getMaxPartitionsPerTenant()
                        : 0);
        params.put(
                "max_qps_per_tenant",
                request.getMaxQpsPerTenant() != null ? request.getMaxQpsPerTenant() : 0);
        params.put(
                "fair_share_weight",
                request.getFairShareWeight() != null ? request.getFairShareWeight() : 1);
        params.put("enabled", request.getEnabled() != null ? request.getEnabled() : true);
        params.put("description", request.getDescription());
        quotaPolicyMapper.insert(params);
        cacheInvalidationService.evictQuotaPolicies(tenantId);
        Long id = ((Number) params.get("id")).longValue();
        return quotaPolicyMapper.selectById(tenantId, id);
    }

    @Override
    public Map<String, Object> update(Long id, QuotaPolicySaveRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        Map<String, Object> existing =
                Guard.requireFound(
                        quotaPolicyMapper.selectById(tenantId, id), "quota policy not found");
        Map<String, Object> params = new HashMap<>();
        params.put("tenant_id", tenantId);
        params.put("id", id);
        params.put(
                "max_running_jobs_per_tenant",
                request.getMaxRunningJobsPerTenant() != null
                        ? request.getMaxRunningJobsPerTenant()
                        : existing.get("max_running_jobs_per_tenant"));
        params.put(
                "max_partitions_per_tenant",
                request.getMaxPartitionsPerTenant() != null
                        ? request.getMaxPartitionsPerTenant()
                        : existing.get("max_partitions_per_tenant"));
        params.put(
                "max_qps_per_tenant",
                request.getMaxQpsPerTenant() != null
                        ? request.getMaxQpsPerTenant()
                        : existing.get("max_qps_per_tenant"));
        params.put(
                "fair_share_weight",
                request.getFairShareWeight() != null
                        ? request.getFairShareWeight()
                        : existing.get("fair_share_weight"));
        params.put(
                "enabled",
                request.getEnabled() != null ? request.getEnabled() : existing.get("enabled"));
        params.put(
                "description",
                request.getDescription() != null
                        ? request.getDescription()
                        : existing.get("description"));
        quotaPolicyMapper.update(params);
        cacheInvalidationService.evictQuotaPolicies(tenantId);
        return quotaPolicyMapper.selectById(tenantId, id);
    }

    @Override
    public void toggle(Long id, String tenantId, Boolean enabled) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        int rows = quotaPolicyMapper.toggleEnabled(resolved, id, enabled);
        if (rows == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "quota policy not found");
        }
        cacheInvalidationService.evictQuotaPolicies(resolved);
    }
}
