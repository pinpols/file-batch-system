package com.example.batch.orchestrator.application.ratelimit;

import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 租户级动作限流门面：{@link RateLimitAction} 决定读哪个配额配置，然后委托 {@link TokenBucketRateLimiter} 做 Redis 固定窗口计数。
 *
 * <p>放行短路：
 *
 * <ul>
 *   <li>{@code governance.rateLimit.enabled=false}：功能开关关闭时直接放行（生产紧急关闸用）。
 *   <li>{@code tenantId} 为空：无法归属到租户则不限流——防御式处理内部调用缺失 tenantId 的场景， 限流只对已识别的租户生效。
 * </ul>
 *
 * <p>未登记的 action 视为 max=0 即直接拒绝（走底层 {@code maxPerMinute <= 0} 的放行逻辑实际上会通过—— 见 {@link
 * TokenBucketRateLimiter#tryConsume}），注册新 action 时务必同步在此分支补 quota 源。
 */
@Component
@RequiredArgsConstructor
public class TenantActionRateLimiter {

  private final BatchOrchestratorGovernanceProperties governance;
  private final TokenBucketRateLimiter limiter;
  private final Clock clock;

  public boolean tryConsume(String tenantId, RateLimitAction action) {
    if (!governance.rateLimit().isEnabled()) {
      return true;
    }
    if (tenantId == null || tenantId.isBlank()) {
      return true;
    }
    long max;
    if (action == RateLimitAction.LAUNCH) {
      max = governance.rateLimit().getMaxNewRequestsPerTenantPerMinute();
    } else if (action == RateLimitAction.DISPATCH_RELEASE) {
      max = governance.rateLimit().getMaxReleaseRequestsPerTenantPerMinute();
    } else {
      max = 0;
    }
    return limiter.tryConsume(tenantId, action.name(), max, clock.millis());
  }
}
