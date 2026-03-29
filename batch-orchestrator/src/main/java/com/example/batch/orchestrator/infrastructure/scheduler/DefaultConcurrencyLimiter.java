package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
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
    private final QuotaRuntimeStateService quotaRuntimeStateService;
    private final ResourceSchedulerProperties resourceSchedulerProperties;

    @Override
    public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            return ResourceCheck.allow();
        }

        long globalCap = resourceSchedulerProperties.getGlobalMaxRunningJobs();
        if (globalCap > 0) {
            long activeAll = jobInstanceMapper.countActiveAll();
            if (activeAll + 1 > globalCap) {
                return ResourceCheck.waitForCapacity(
                        "GLOBAL_RUNNING_JOB_LIMIT",
                        "global running jobs exceed cap"
                );
            }
        }

        TenantQuotaPolicyRecord quotaPolicy = resolveQuotaPolicy(request.getTenantId());
        long tenantActiveJobs = jobInstanceMapper.countActiveByTenant(request.getTenantId());

        if (quotaPolicy != null
                && StringUtils.hasText(quotaPolicy.fairShareGroup())
                && quotaPolicy.groupSharedMaxRunningJobs() != null
                && quotaPolicy.groupSharedMaxRunningJobs() > 0) {
            long groupActive = jobInstanceMapper.countActiveByFairShareGroup(quotaPolicy.fairShareGroup());
            if (groupActive >= quotaPolicy.groupSharedMaxRunningJobs()) {
                return ResourceCheck.waitForCapacity(
                        "FAIR_SHARE_GROUP_JOB_LIMIT",
                        "fair-share group job cap reached for group " + quotaPolicy.fairShareGroup()
                );
            }
        }

        if (quotaPolicy != null
                && quotaPolicy.maxRunningJobsPerTenant() != null
                && quotaPolicy.maxRunningJobsPerTenant() > 0) {
            int burst = quotaPolicy.burstLimit() == null ? 0 : Math.max(0, quotaPolicy.burstLimit());
            ResourceCheck burstCheck = quotaRuntimeStateService.evaluateAndReserve(
                    request.getTenantId(),
                    "TENANT_JOBS",
                    request.getTenantId(),
                    quotaPolicy.quotaResetPolicy(),
                    quotaPolicy.maxRunningJobsPerTenant(),
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
                && StringUtils.hasText(queue.queueCode())
                && queue.maxRunningJobs() != null
                && queue.maxRunningJobs() > 0) {
            long queueActiveJobs = jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.queueCode());
            int qburst = queue.burstLimit() == null ? 0 : Math.max(0, queue.burstLimit());
            ResourceCheck burstCheck = quotaRuntimeStateService.evaluateAndReserve(
                    request.getTenantId(),
                    "QUEUE_JOBS",
                    queue.queueCode(),
                    queue.quotaResetPolicy(),
                    queue.maxRunningJobs(),
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
