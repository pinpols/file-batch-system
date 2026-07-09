package io.github.pinpols.batch.orchestrator.application.ratelimit;

import io.github.pinpols.batch.orchestrator.config.RateLimitProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 租户级动作限流门面：{@link RateLimitAction} 决定读哪个配额配置，然后委托 {@link TokenBucketRateLimiter} 做 Redis 分布式令牌桶限流。
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

  /**
   * 限流拒绝计数：同步 REST 热路径（claim/report/register）命中限流被拒（将抛 429）时自增。tag 仅 {@code action} （基数 = {@link
   * RateLimitAction} 枚举数，很小）。<b>刻意不带 tenant tag</b>：租户数可能很多，作为 tag 会打爆监控时序基数； per-action
   * 维度已足够做容量规划/滥用侦测，租户级归因走日志。这里是同步限流拒绝的唯一计数点，异步 LAUNCH 路径另有 {@code
   * batch.trigger.launch.failed.total{reason=rate_limited}}。
   */
  static final String METRIC_REJECTED = "batch.ratelimit.rejected.total";

  // 规则 #9:action→配额解析改路由表(避免 if-chain)。未登记 action 走 getOrDefault → 0 → 由底层放行。
  private static final Map<RateLimitAction, ToLongFunction<RateLimitProperties>> LIMIT_RESOLVERS =
      Map.of(
          RateLimitAction.LAUNCH, RateLimitProperties::getMaxNewRequestsPerTenantPerMinute,
          RateLimitAction.DISPATCH_RELEASE,
              RateLimitProperties::getMaxReleaseRequestsPerTenantPerMinute,
          RateLimitAction.WORKER_REGISTER,
              RateLimitProperties::getMaxRegisterRequestsPerTenantPerMinute,
          RateLimitAction.TASK_CLAIM, RateLimitProperties::getMaxClaimRequestsPerTenantPerMinute,
          RateLimitAction.TASK_REPORT, RateLimitProperties::getMaxReportRequestsPerTenantPerMinute);

  private final BatchOrchestratorGovernanceProperties governance;
  private final TokenBucketRateLimiter limiter;
  private final MeterRegistry meterRegistry;

  public boolean tryConsume(String tenantId, RateLimitAction action) {
    if (!governance.rateLimit().isEnabled()) {
      return true;
    }
    if (tenantId == null || tenantId.isBlank()) {
      return true;
    }
    long max =
        LIMIT_RESOLVERS.getOrDefault(action, props -> 0L).applyAsLong(governance.rateLimit());
    boolean allowed = limiter.tryConsume(tenantId, action.name(), max);
    if (!allowed) {
      // 唯一同步拒绝计数点：两个 controller 都经此门面,拒绝后各自抛 429。这里计数免去在 controller 重复埋点。
      Counter.builder(METRIC_REJECTED)
          .tags(Tags.of("action", action.name()))
          .register(meterRegistry)
          .increment();
    }
    return allowed;
  }
}
