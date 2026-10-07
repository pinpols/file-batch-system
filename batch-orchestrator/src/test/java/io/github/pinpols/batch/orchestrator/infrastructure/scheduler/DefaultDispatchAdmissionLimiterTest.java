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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("派发准入限流器: 队列与租户配额耗尽时的拒绝原因及零值兼容口径")
class DefaultDispatchAdmissionLimiterTest {

  private final OrchestratorConfigCacheService cache = mock(OrchestratorConfigCacheService.class);
  private final TokenBucketRateLimiter limiter = mock(TokenBucketRateLimiter.class);
  private final DefaultDispatchAdmissionLimiter admission =
      new DefaultDispatchAdmissionLimiter(cache, limiter);

  @Test
  @DisplayName("租户级每秒配额耗尽时拒绝本次派发,拒绝原因指向租户级限流")
  void shouldRejectByTenantQps_whenTenantQuotaExhausted() {
    ResourceSchedulingRequest request = request();
    when(cache.findEnabledQuotaPolicy("t1")).thenReturn(policy(20));
    when(limiter.tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_QUEUE_q1", 10)).thenReturn(true);
    when(limiter.tryConsumePerSecond("t1", "SCHEDULER_DISPATCH_TENANT", 20)).thenReturn(false);

    ResourceCheck result = admission.check(request, queue(10));

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("TENANT_DISPATCH_QPS_LIMIT");
  }

  @Test
  @DisplayName("队列配额耗尽时先按队列维度拒绝,不再消耗租户级配额")
  void shouldCheckQueueQpsFirst_whenQueueQuotaExhausted() {
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
  @DisplayName("队列与租户配额均为零时视为不限制,准入直接放行")
  void shouldAllow_whenAllQpsLimitsAreZero() {
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
