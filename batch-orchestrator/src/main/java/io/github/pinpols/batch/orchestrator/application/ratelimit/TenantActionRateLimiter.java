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
   * 限流拒绝计数：本门面 {@link #tryConsume} 返回 false 时按 {@code action} 自增。tag 仅 {@code action}（基数 = {@link
   * RateLimitAction} 枚举数，很小）。<b>刻意不带 tenant tag</b>：租户数可能很多，作为 tag 会打爆监控时序基数；per-action
   * 维度已足够做容量规划/滥用侦测，租户级归因走日志。
   *
   * <p><b>覆盖本门面全部 4 个调用点，但拒绝的对外语义并不一致——据此告警须按 action 区分</b>：
   *
   * <ul>
   *   <li>{@code TASK_CLAIM} / {@code TASK_REPORT}（{@code TaskController}，同步 REST）→ 拒绝抛 <b>429</b>。
   *   <li>{@code WORKER_REGISTER}（{@code WorkerController}，同步 REST）→ 拒绝抛 <b>429</b>。
   *   <li>{@code LAUNCH}（{@code LaunchApplicationService}，同步 {@code POST /api/triggers/launch}）→
   *       拒绝抛 <b>429</b>。（异步 Kafka LAUNCH 路径不经本门面，其限流命中另计在 {@code
   *       batch.trigger.launch.failed.total{reason=rate_limited}}。）
   *   <li>{@code DISPATCH_RELEASE}（{@code WaitingPartitionDispatchScheduler}，内部派发调度）→ 拒绝仅 {@code
   *       return} 让本轮不派发（<b>内部派发背压，不产生客户端 429</b>），下一轮调度自然重试。
   * </ul>
   *
   * <p>因此本指标总和 <b>不等于</b>客户端 429 速率——把 {@code DISPATCH_RELEASE} 的内部背压算进 429 告警会失真；要「429 速率」须排除
   * {@code action=DISPATCH_RELEASE}。
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
      // 门面级唯一拒绝计数点,覆盖全部 4 个调用点(TaskController/WorkerController/LaunchApplicationService
      // 拒绝后各自抛 429;WaitingPartitionDispatchScheduler 拒绝仅内部派发背压 return,非 REST 非 429)。
      // 在此计数免去各调用点重复埋点;DISPATCH_RELEASE 语义见 METRIC_REJECTED javadoc(告警须按 action 区分)。
      Counter.builder(METRIC_REJECTED)
          .tags(Tags.of("action", action.name()))
          .register(meterRegistry)
          .increment();
    }
    return allowed;
  }
}
