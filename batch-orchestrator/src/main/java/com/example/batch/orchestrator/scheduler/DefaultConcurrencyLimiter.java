package com.example.batch.orchestrator.scheduler;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import com.example.batch.orchestrator.scheduler.quota.QuotaRuntimeStateService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultConcurrencyLimiter implements ConcurrencyLimiter {

    private final JobInstanceMapper jobInstanceMapper;
    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private final QuotaRuntimeStateService quotaRuntimeStateService;
    private final ResourceSchedulerProperties resourceSchedulerProperties;

    @Override
    public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return ResourceCheck.allow();
        }
        TenantQuotaPolicyRecord quotaPolicy = resolveQuotaPolicy(request.getTenantId());
        long tenantActiveJobs = jobInstanceMapper.countActiveByTenant(request.getTenantId());

        if (quotaPolicy != null
                && StringUtils.hasText(quotaPolicy.getFairShareGroup())
                && quotaPolicy.getGroupSharedMaxRunningJobs() != null
                && quotaPolicy.getGroupSharedMaxRunningJobs() > 0) {
            long groupActive = jobInstanceMapper.countActiveByFairShareGroup(quotaPolicy.getFairShareGroup());
            if (groupActive >= quotaPolicy.getGroupSharedMaxRunningJobs()) {
                return ResourceCheck.waitForCapacity(
                        "FAIR_SHARE_GROUP_JOB_LIMIT",
                        "fair-share group job cap reached for group " + quotaPolicy.getFairShareGroup()
                );
            }
        }

        if (quotaPolicy != null
                && quotaPolicy.getMaxRunningJobsPerTenant() != null
                && quotaPolicy.getMaxRunningJobsPerTenant() > 0) {
            int burst = quotaPolicy.getBurstLimit() == null ? 0 : Math.max(0, quotaPolicy.getBurstLimit());
            ResourceCheck burstCheck = quotaRuntimeStateService.evaluateAndReserve(
                    request.getTenantId(),
                    "TENANT_JOBS",
                    request.getTenantId(),
                    quotaPolicy.getQuotaResetPolicy(),
                    quotaPolicy.getMaxRunningJobsPerTenant(),
                    burst,
                    tenantActiveJobs,
                    1,
                    resourceSchedulerProperties.getQuotaResetSlidingWindowHours(),
                    "TENANT_JOB_LIMIT",
                    "tenant running jobs exceed quota (including burst)"
            );
            if (!burstCheck.allowed()) {
                return burstCheck;
            }
        }

        if (queue != null
                && StringUtils.hasText(queue.getQueueCode())
                && queue.getMaxRunningJobs() != null
                && queue.getMaxRunningJobs() > 0) {
            long queueActiveJobs = jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.getQueueCode());
            int qburst = queue.getBurstLimit() == null ? 0 : Math.max(0, queue.getBurstLimit());
            ResourceCheck burstCheck = quotaRuntimeStateService.evaluateAndReserve(
                    request.getTenantId(),
                    "QUEUE_JOBS",
                    queue.getQueueCode(),
                    queue.getQuotaResetPolicy(),
                    queue.getMaxRunningJobs(),
                    qburst,
                    queueActiveJobs,
                    1,
                    resourceSchedulerProperties.getQuotaResetSlidingWindowHours(),
                    "QUEUE_JOB_LIMIT",
                    "resource queue running jobs exceed limit (including burst)"
            );
            if (!burstCheck.allowed()) {
                return burstCheck;
            }
        }
        return ResourceCheck.allow();
    }

    private TenantQuotaPolicyRecord resolveQuotaPolicy(String tenantId) {
        List<TenantQuotaPolicyRecord> policies = tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true);
        return policies == null || policies.isEmpty() ? null : policies.getFirst();
    }
}
