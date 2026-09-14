package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.ratelimit.TokenBucketRateLimiter;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import org.junit.jupiter.api.Test;

class DefaultDispatchAdmissionLimiterTest {

  private final OrchestratorConfigCacheService cache = mock(OrchestratorConfigCacheService.class);
  private final TokenBucketRateLimiter limiter = mock(TokenBucketRateLimiter.class);
  private final DefaultDispatchAdmissionLimiter admission =
      new DefaultDispatchAdmissionLimiter(cache, limiter);

  @Test
  void tenantQpsExhaustionDefersDispatch() {
    ResourceSchedulingRequest request = request();
    when(cache.findEnabledQuotaPolicy("t1")).thenReturn(policy(20));
    when(limiter.tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_QUEUE_q1", 10)).thenReturn(true);
    when(limiter.tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_TENANT", 20)).thenReturn(false);

    ResourceCheck result = admission.check(request, queue(10));

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("TENANT_DISPATCH_QPS_LIMIT");
  }

  @Test
  void queueQpsIsCheckedBeforeTenantToAvoidCrossQueueStarvation() {
    ResourceSchedulingRequest request = request();
    when(cache.findEnabledQuotaPolicy("t1")).thenReturn(policy(20));
    when(limiter.tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_QUEUE_q1", 10)).thenReturn(false);

    ResourceCheck result = admission.check(request, queue(10));

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("QUEUE_DISPATCH_QPS_LIMIT");
    verify(limiter).tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_QUEUE_q1", 10);
    verify(limiter, never()).tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_TENANT", 20);
  }

  @Test
  void zeroLimitsKeepBackwardCompatibleUnlimitedBehavior() {
    ResourceSchedulingRequest request = request();
    when(cache.findEnabledQuotaPolicy("t1")).thenReturn(policy(0));

    assertThat(admission.check(request, queue(0)).allowed()).isTrue();
  }

  private static ResourceSchedulingRequest request() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId("t1");
    return request;
  }

  private static TenantQuotaPolicyEntity policy(int maxQps) {
    return new TenantQuotaPolicyEntity(
        1L, "t1", "p1", 10, 20, maxQps, 1, null, 0, 0, "NONE", null, true, null);
  }

  private static ResourceQueueEntity queue(int maxQps) {
    return new ResourceQueueEntity(
        1L, "t1", "q1", "queue", "DEFAULT", 10, 20, maxQps, "IMPORT", null, null, 1, null, 0,
        "NONE", null, true);
  }
}
