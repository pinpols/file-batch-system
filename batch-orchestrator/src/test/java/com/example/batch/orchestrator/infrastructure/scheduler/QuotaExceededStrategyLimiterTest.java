package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import com.example.batch.orchestrator.domain.scheduling.ResourceCheck;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V89 验证：tenant_quota_policy.exceeded_strategy 把租户超额检查的结果按策略转换。
 *
 * <ul>
 *   <li>{@code REJECT} → ResourceCheck.reject (failFast=true) — launch 立刻抛 BizException
 *   <li>{@code QUEUE_DEFER} → ResourceCheck.waitForCapacity — partition 留 WAITING 等下 tick
 *   <li>{@code DEGRADE_PRIORITY} → waitForCapacity 但 reasonCode 末尾打 _DEGRADED 标记， 供
 *       DefaultResourceScheduler 把决策 priority/band 砍到最低
 *   <li>null / 未识别策略 → 默认 REJECT（向后兼容）
 * </ul>
 */
class QuotaExceededStrategyLimiterTest {

  private DefaultConcurrencyLimiter limiter;
  private JobInstanceMapper jobInstanceMapper;
  private OrchestratorConfigCacheService configCache;
  private QuotaRuntimeStateService quotaRuntime;

  @BeforeEach
  void setUp() {
    jobInstanceMapper = mock(JobInstanceMapper.class);
    configCache = mock(OrchestratorConfigCacheService.class);
    quotaRuntime = mock(QuotaRuntimeStateService.class);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    ResourceSchedulerProperties resScheduler = mock(ResourceSchedulerProperties.class);
    when(governance.resourceScheduler()).thenReturn(resScheduler);
    when(resScheduler.getGlobalMaxRunningJobs()).thenReturn(0L); // 关掉全局闸门
    when(resScheduler.getQuotaResetSlidingWindowHours()).thenReturn(24);

    limiter =
        new DefaultConcurrencyLimiter(jobInstanceMapper, configCache, quotaRuntime, governance);
  }

  @Test
  void rejectStrategy_shouldFailFast() {
    arrangeBurstExceeded("REJECT");

    ResourceCheck check = limiter.check(request(), null);

    assertThat(check.allowed()).isFalse();
    assertThat(check.failFast()).isTrue();
    assertThat(check.reasonCode()).isEqualTo("TENANT_JOB_LIMIT");
  }

  @Test
  void queueDeferStrategy_shouldWaitForCapacity() {
    arrangeBurstExceeded("QUEUE_DEFER");

    ResourceCheck check = limiter.check(request(), null);

    assertThat(check.allowed()).isFalse();
    assertThat(check.failFast()).isFalse();
    assertThat(check.reasonCode()).isEqualTo("TENANT_JOB_LIMIT");
  }

  @Test
  void degradePriorityStrategy_shouldMarkReasonWithDegradedSuffix() {
    arrangeBurstExceeded("DEGRADE_PRIORITY");

    ResourceCheck check = limiter.check(request(), null);

    assertThat(check.allowed()).isFalse();
    assertThat(check.failFast()).isFalse();
    assertThat(check.reasonCode()).isEqualTo("TENANT_JOB_LIMIT_DEGRADED");
  }

  @Test
  void nullStrategy_shouldFallBackToReject() {
    arrangeBurstExceeded(null);

    ResourceCheck check = limiter.check(request(), null);

    assertThat(check.allowed()).isFalse();
    assertThat(check.failFast()).isTrue();
  }

  /** quota 未触顶时 limiter 必须 allow，与 strategy 无关。 */
  @Test
  void allowedQuota_shouldNotApplyStrategy() {
    when(jobInstanceMapper.countActiveByTenant(anyString())).thenReturn(1L);
    when(configCache.findEnabledQuotaPolicy(anyString())).thenReturn(policy("REJECT"));
    when(quotaRuntime.evaluateAndReserve(any())).thenReturn(ResourceCheck.allow());

    ResourceCheck check = limiter.check(request(), null);

    assertThat(check.allowed()).isTrue();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private void arrangeBurstExceeded(String strategy) {
    when(jobInstanceMapper.countActiveByTenant(anyString())).thenReturn(99L);
    when(configCache.findEnabledQuotaPolicy(anyString())).thenReturn(policy(strategy));
    when(quotaRuntime.evaluateAndReserve(any()))
        .thenReturn(
            ResourceCheck.waitForCapacity(
                "TENANT_JOB_LIMIT", "tenant running jobs exceed quota (including burst)"));
  }

  private static TenantQuotaPolicyEntity policy(String exceededStrategy) {
    return new TenantQuotaPolicyEntity(
        1L, "tenant-x", "default", 10, 100, 0, 1, null, 0, 0, "NONE", null, true, exceededStrategy);
  }

  private static ResourceSchedulingRequest request() {
    ResourceSchedulingRequest r = new ResourceSchedulingRequest();
    r.setTenantId("tenant-x");
    r.setJobCode("JOB_Q");
    r.setQueueCode("default");
    r.setWorkerGroup("import");
    r.setWorkerType("IMPORT");
    return r;
  }
}
