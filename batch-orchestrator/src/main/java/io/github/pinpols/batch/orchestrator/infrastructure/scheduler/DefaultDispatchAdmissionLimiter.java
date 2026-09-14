package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TokenBucketRateLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.DispatchAdmissionLimiter;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 把配置表中的 maxQps 真正接到统一调度入口，所有 Orchestrator 副本共享 Redis 令牌桶。 */
@Component
@RequiredArgsConstructor
public class DefaultDispatchAdmissionLimiter implements DispatchAdmissionLimiter {

  private static final String TENANT_ACTION = "SCHEDULER_DISPATCH_TENANT";
  private static final String QUEUE_ACTION_PREFIX = "SCHEDULER_DISPATCH_QUEUE_";

  private final OrchestratorConfigCacheService configCacheService;
  private final TokenBucketRateLimiter tokenBucketRateLimiter;

  @Override
  public ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueEntity queue) {
    if (EmptyChecks.isNull(request) || !Texts.hasText(request.getTenantId())) {
      return ResourceCheck.allow();
    }
    // 先扣更细粒度的队列桶。若队列已满，不能先消耗租户令牌，否则一个热点队列会让同租户
    // 其它健康队列提前被限流。两个 Redis key 无法跨桶原子回滚；该顺序把保守误差限制在本队列内。
    if (EmptyChecks.isNotNull(queue)
        && Texts.hasText(queue.queueCode())
        && EmptyChecks.isNotNull(queue.maxQps())
        && queue.maxQps() > 0
        && !tokenBucketRateLimiter.tryConsumePerSecond(
            request.getTenantId(), QUEUE_ACTION_PREFIX + queue.queueCode(), queue.maxQps())) {
      return ResourceCheck.waitForCapacity(
          "QUEUE_DISPATCH_QPS_LIMIT", "resource queue dispatch rate exceeds maxQps");
    }
    TenantQuotaPolicyEntity policy =
        configCacheService.findEnabledQuotaPolicy(request.getTenantId());
    if (EmptyChecks.isNotNull(policy)
        && EmptyChecks.isNotNull(policy.maxQpsPerTenant())
        && policy.maxQpsPerTenant() > 0
        && !tokenBucketRateLimiter.tryConsumePerSecond(
            request.getTenantId(), TENANT_ACTION, policy.maxQpsPerTenant())) {
      return ResourceCheck.waitForCapacity(
          "TENANT_DISPATCH_QPS_LIMIT", "tenant dispatch rate exceeds maxQpsPerTenant");
    }
    return ResourceCheck.allow();
  }
}
