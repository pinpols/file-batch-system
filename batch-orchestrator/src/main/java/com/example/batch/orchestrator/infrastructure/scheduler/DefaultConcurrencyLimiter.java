package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultConcurrencyLimiter implements ConcurrencyLimiter {

  private final JobInstanceMapper jobInstanceMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final QuotaRuntimeStateService quotaRuntimeStateService;
  private final BatchOrchestratorGovernanceProperties governance;

  @Override
  public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueRecord queue) {
    if (request == null || !StringUtils.hasText(request.getTenantId())) {
      return ResourceCheck.allow();
    }

    ResourceCheck globalCheck = checkGlobalLimit();
    if (!globalCheck.allowed()) {
      return globalCheck;
    }

    TenantQuotaPolicyRecord quotaPolicy = resolveQuotaPolicy(request.getTenantId());
    ResourceCheck tenantCheck = checkTenantLimit(request, quotaPolicy);
    if (!tenantCheck.allowed()) {
      return tenantCheck;
    }

    ResourceCheck queueCheck = checkQueueLimit(request, queue);
    if (!queueCheck.allowed()) {
      return queueCheck;
    }

    return ResourceCheck.allow();
  }

  private ResourceCheck checkGlobalLimit() {
    long globalCap = governance.resourceScheduler().getGlobalMaxRunningJobs();
    if (globalCap > 0) {
      long activeAll = jobInstanceMapper.countActiveAll();
      if (activeAll + 1 > globalCap) {
        return ResourceCheck.waitForCapacity(
            "GLOBAL_RUNNING_JOB_LIMIT", "global running jobs exceed cap");
      }
    }
    return ResourceCheck.allow();
  }

  private ResourceCheck checkTenantLimit(
      ResourceSchedulingRequest request, TenantQuotaPolicyRecord quotaPolicy) {
    if (quotaPolicy == null) {
      return ResourceCheck.allow();
    }

    if (StringUtils.hasText(quotaPolicy.fairShareGroup())
        && quotaPolicy.groupSharedMaxRunningJobs() != null
        && quotaPolicy.groupSharedMaxRunningJobs() > 0) {
      long groupActive =
          jobInstanceMapper.countActiveByFairShareGroup(quotaPolicy.fairShareGroup());
      if (groupActive >= quotaPolicy.groupSharedMaxRunningJobs()) {
        return ResourceCheck.waitForCapacity(
            "FAIR_SHARE_GROUP_JOB_LIMIT",
            "fair-share group job cap reached for group " + quotaPolicy.fairShareGroup());
      }
    }

    if (quotaPolicy.maxRunningJobsPerTenant() != null
        && quotaPolicy.maxRunningJobsPerTenant() > 0) {
      long tenantActiveJobs = jobInstanceMapper.countActiveByTenant(request.getTenantId());
      int burst = quotaPolicy.burstLimit() == null ? 0 : Math.max(0, quotaPolicy.burstLimit());
      ResourceCheck burstCheck =
          quotaRuntimeStateService.evaluateAndReserve(
              new QuotaRuntimeStateService.QuotaReservationRequest(
                  new QuotaRuntimeStateService.QuotaReservationOwner(
                      request.getTenantId(), "TENANT_JOBS", request.getTenantId()),
                  new QuotaRuntimeStateService.QuotaReservationPolicy(
                      quotaPolicy.quotaResetPolicy(),
                      quotaPolicy.maxRunningJobsPerTenant(),
                      burst,
                      governance.resourceScheduler().getQuotaResetSlidingWindowHours()),
                  tenantActiveJobs,
                  1,
                  new QuotaRuntimeStateService.QuotaReservationReason(
                      "TENANT_JOB_LIMIT", "tenant running jobs exceed quota (including burst)")));
      if (!burstCheck.allowed()) {
        return burstCheck;
      }
    }

    return ResourceCheck.allow();
  }

  private ResourceCheck checkQueueLimit(
      ResourceSchedulingRequest request, ResourceQueueRecord queue) {
    if (queue == null
        || !StringUtils.hasText(queue.queueCode())
        || queue.maxRunningJobs() == null
        || queue.maxRunningJobs() <= 0) {
      return ResourceCheck.allow();
    }

    long queueActiveJobs =
        jobInstanceMapper.countActiveByTenantAndQueueCode(request.getTenantId(), queue.queueCode());
    int qburst = queue.burstLimit() == null ? 0 : Math.max(0, queue.burstLimit());
    ResourceCheck burstCheck =
        quotaRuntimeStateService.evaluateAndReserve(
            new QuotaRuntimeStateService.QuotaReservationRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner(
                    request.getTenantId(), "QUEUE_JOBS", queue.queueCode()),
                new QuotaRuntimeStateService.QuotaReservationPolicy(
                    queue.quotaResetPolicy(),
                    queue.maxRunningJobs(),
                    qburst,
                    governance.resourceScheduler().getQuotaResetSlidingWindowHours()),
                queueActiveJobs,
                1,
                new QuotaRuntimeStateService.QuotaReservationReason(
                    "QUEUE_JOB_LIMIT",
                    "resource queue running jobs exceed limit (including" + " burst)")));
    if (!burstCheck.allowed()) {
      return burstCheck;
    }
    return ResourceCheck.allow();
  }

  private TenantQuotaPolicyRecord resolveQuotaPolicy(String tenantId) {
    return configCacheService.findEnabledQuotaPolicy(tenantId);
  }
}
