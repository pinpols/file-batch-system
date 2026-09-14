package io.github.pinpols.batch.orchestrator.application.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeoutException;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.RateLimitProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集群级令牌桶限流器：基于 Bucket4j + Redis（Lettuce）分布式后端，是名副其实的 token bucket。
 *
 * <p><b>语义</b>：每 (tenant, action) 一个桶，容量 = 该 action 的每分钟阈值，refill = greedy（在 1 分钟内平滑、 按比例持续补充
 * {@code maxPerMinute} 个令牌）。{@code tryConsume(1)} 成功放行、失败拒绝。
 *
 * <p><b>相较旧固定窗口的改进</b>：旧实现按 {@code floor(now/60s)} 分窗独立计数，相邻窗口边界可短暂放行 2× 配额（突发缺陷）。令牌桶的瞬时突发上限就是桶容量 =
 * {@code maxPerMinute}（< 旧的 2×），且 greedy 补充令令牌 平滑回填而非整分钟一次性投放，从而消除窗口边界翻倍突发。稳态速率仍为 {@code
 * maxPerMinute}/min。
 *
 * <p><b>配置变更</b>：Bucket4j 会把桶配置和令牌状态一起持久化，单纯重启应用不会更新已有桶。本实现启用 Bucket4j 的隐式配置替换；当 {@code
 * bucket-configuration-version} 增大时，Redis 中已有桶会在下一次消费时原子更新，并按比例继承剩余令牌。这样无需删除 Redis key，滚动发布中的旧副本也不能覆盖新配置。
 *
 * <p><b>时钟回拨</b>：不再需要旧的自研 CAS 回拨保护。bucket4j 分布式 CAS proxy manager 的 refill 时间源其实是<b>客户端墙钟</b>
 * （{@code TimeMeter.SYSTEM_MILLISECONDS}，Lua 不调 Redis TIME），但移除保护仍安全：{@code BucketState} 的 refill
 * 有<b>单调钳制</b> （{@code currentTimeNanos <= lastRefillTimeNanos} 时直接 return，回拨既不补令牌也不回挪
 * lastRefillTime），故单机回拨最多导致少补令牌 = 更严限流（安全方向），永远不会像旧固定窗口那样复活老窗口 key 叠加计数击穿配额。
 *
 * <p><b>降级方向</b>：Console/API 每分钟限流在 Redis 不可达时保持历史 fail-open；内部任务派发每秒限流
 * fail-closed，使任务留在 WAITING，避免 Redis 故障同时解除执行面保护。
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

  private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

  /**
   * fail-open 计数：Redis 不可达时在无限流保护下放行的请求数。tag {@code reason} 区分 {@code redis_exception}（Lettuce
   * 命令级故障）与 {@code bucket4j_timeout}（配了 requestTimeout 后超时）。tag 基数固定为 2，不带 tenant/action 高基数维度，
   * 供「过去 N 分钟多少请求在无限流保护下放行」告警。
   */
  static final String METRIC_FAILOPEN = "batch.ratelimit.failopen.total";

  static final String METRIC_FAILCLOSED = "batch.ratelimit.failclosed.total";

  static final String FAILOPEN_REASON_REDIS = "redis_exception";
  static final String FAILOPEN_REASON_TIMEOUT = "bucket4j_timeout";

  /**
   * fail-open reason：Redis 短路熔断 OPEN，直接放行未发 Redis 命令（省掉 requestTimeout 阻塞）。与上面两类 reason 一套 counter，
   * 但语义不同——上面是"发了命令但故障"，这个是"熔断判定不健康后连命令都没发"。tag 基数固定，供告警区分持续慢故障 vs 偶发抖动。
   */
  static final String FAILOPEN_REASON_CIRCUIT_OPEN = "circuit_open";

  private final LettuceBasedProxyManager<String> proxyManager;
  private final MeterRegistry meterRegistry;
  private final RedisRateLimitCircuitBreaker circuitBreaker;
  private final RateLimitProperties properties;

  // 按 maxPerMinute 缓存 BucketConfiguration supplier，避免每次 tryConsume 都新建（阈值集合很小，见
  // RateLimitProperties）。
  private final Map<BucketSpec, Supplier<BucketConfiguration>> configSuppliers =
      new ConcurrentHashMap<>();

  private record BucketSpec(long capacity, Duration refillPeriod) {}

  private enum BackendFailureMode {
    FAIL_OPEN,
    FAIL_CLOSED
  }

  public TokenBucketRateLimiter(
      LettuceBasedProxyManager<String> proxyManager,
      MeterRegistry meterRegistry,
      RedisRateLimitCircuitBreaker circuitBreaker,
      RateLimitProperties properties) {
    this.proxyManager = proxyManager;
    this.meterRegistry = meterRegistry;
    this.circuitBreaker = circuitBreaker;
    this.properties = properties;
  }

  public boolean tryConsume(String tenantId, String action, long maxPerMinute) {
    return tryConsume(
        tenantId,
        action,
        new BucketSpec(maxPerMinute, REFILL_PERIOD),
        BackendFailureMode.FAIL_OPEN,
        false);
  }

  /**
   * 内部派发准入的每秒令牌桶。Redis 故障时返回 false，让分区保留 WAITING；不会把后端故障变成无界放行。
   */
  public boolean tryConsumePerSecond(String tenantId, String action, long maxPerSecond) {
    return tryConsume(
        tenantId,
        action,
        new BucketSpec(maxPerSecond, Duration.ofSeconds(1)),
        BackendFailureMode.FAIL_CLOSED,
        true);
  }

  private boolean tryConsume(
      String tenantId,
      String action,
      BucketSpec spec,
      BackendFailureMode failureMode,
      boolean dynamicConfiguration) {
    if (spec.capacity() <= 0 || !Texts.hasText(tenantId) || !Texts.hasText(action)) {
      return true;
    }
    String key = resolveBucketKey(tenantId, action, spec, dynamicConfiguration);
    try {
      // 经短路熔断执行：CLOSED/HALF_OPEN 时正常发 Redis 命令；OPEN 时不发命令、抛 CallNotPermittedException 短路 fail-open。
      return circuitBreaker.call(() -> bucketFor(key, spec).tryConsume(1));
    } catch (CallNotPermittedException ex) {
      return backendFailure(tenantId, action, FAILOPEN_REASON_CIRCUIT_OPEN, failureMode, ex);
    } catch (RedisException | TimeoutException ex) {
      // API 限流保持历史 fail-open；内部派发由 failureMode 选择 fail-closed。
      // 坑：Redis 命令级故障抛 io.lettuce.core.RedisException；但 proxy manager 配了 requestTimeout 后，
      //     超时抛的是 io.github.bucket4j.TimeoutException（不是 RedisException）——必须一并兜住，
      //     否则超时会逃逸 catch，绕开两类入口各自声明的降级方向。
      String reason =
          (ex instanceof TimeoutException) ? FAILOPEN_REASON_TIMEOUT : FAILOPEN_REASON_REDIS;
      return backendFailure(tenantId, action, reason, failureMode, ex);
    }
  }

  private boolean backendFailure(
      String tenantId,
      String action,
      String reason,
      BackendFailureMode failureMode,
      RuntimeException ex) {
    boolean failOpen = failureMode == BackendFailureMode.FAIL_OPEN;
    String metric = failOpen ? METRIC_FAILOPEN : METRIC_FAILCLOSED;
    Counter.builder(metric)
        .tags(Tags.of("reason", reason))
        .register(meterRegistry)
        .increment();
    log.warn(
        "Redis rate-limit unavailable; {}: tenantId={}, action={}, cause={}",
        failOpen ? "fail-open" : "fail-closed",
        tenantId,
        action,
        ex.getMessage());
    log.debug("Redis rate-limit backend failure", ex);
    return failOpen;
  }

  private String resolveBucketKey(
      String tenantId, String action, BucketSpec spec, boolean dynamicConfiguration) {
    if (!dynamicConfiguration) {
      return BatchRedisKeys.rateLimitBucket(tenantId, action);
    }
    // 租户/队列 maxQps 来自热更新配置表，不能依赖部署级 bucketConfigurationVersion。
    // 阈值与周期进入 key 后，配置变更立即启用新桶；旧 key 由 Bucket4j 过期策略自然回收。
    String versionedAction = "%s_cap_%d_period_ms_%d"
        .formatted(action, spec.capacity(), spec.refillPeriod().toMillis());
    return BatchRedisKeys.rateLimitBucket(tenantId, versionedAction);
  }

  private BucketProxy bucketFor(String key, BucketSpec spec) {
    return proxyManager
        .builder()
        .withImplicitConfigurationReplacement(
            properties.getBucketConfigurationVersion(), TokensInheritanceStrategy.PROPORTIONALLY)
        .build(key, configSupplierFor(spec));
  }

  private Supplier<BucketConfiguration> configSupplierFor(BucketSpec spec) {
    return configSuppliers.computeIfAbsent(spec, this::buildConfigSupplier);
  }

  private Supplier<BucketConfiguration> buildConfigSupplier(BucketSpec spec) {
    BucketConfiguration configuration = BucketConfiguration.builder()
        .addLimit(Bandwidth.builder()
            .capacity(spec.capacity())
            .refillGreedy(spec.capacity(), spec.refillPeriod())
            .build())
        .build();
    return () -> configuration;
  }
}
