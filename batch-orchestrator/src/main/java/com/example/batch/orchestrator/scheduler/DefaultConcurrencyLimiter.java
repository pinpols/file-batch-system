package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultConcurrencyLimiter implements ConcurrencyLimiter {

    private final JobInstanceMapper jobInstanceMapper;
    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;

    @Override
    public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return ResourceCheck.allow();
        }
        TenantQuotaPolicyRecord quotaPolicy = resolveQuotaPolicy(request.getTenantId());
        long tenantActiveJobs = jobInstanceMapper.countActiveByTenant(request.getTenantId());
        if (quotaPolicy != null
                && quotaPolicy.getMaxRunningJobsPerTenant() != null
                && quotaPolicy.getMaxRunningJobsPerTenant() > 0
                && tenantActiveJobs >= quotaPolicy.getMaxRunningJobsPerTenant()) {
            return ResourceCheck.waitForCapacity(
                    "TENANT_JOB_LIMIT",
                    "tenant running jobs exceed quota"
            );
        }
        if (queue != null
                && StringUtils.hasText(queue.getQueueCode())
                && queue.getMaxRunningJobs() != null
                && queue.getMaxRunningJobs() > 0) {
            long queueActiveJobs = jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.getQueueCode());
            if (queueActiveJobs >= queue.getMaxRunningJobs()) {
                return ResourceCheck.waitForCapacity(
                        "QUEUE_JOB_LIMIT",
                        "resource queue running jobs exceed limit"
                );
            }
        }
        return ResourceCheck.allow();
    }

    private TenantQuotaPolicyRecord resolveQuotaPolicy(String tenantId) {
        List<TenantQuotaPolicyRecord> policies = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        return policies == null || policies.isEmpty() ? null : policies.get(0);
    }
}
