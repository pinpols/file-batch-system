package io.github.pinpols.batch.orchestrator.application.ratelimit;

import io.github.bucket4j.TimeoutException;
import io.github.pinpols.batch.orchestrator.config.RateLimitProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 限流器 Redis 交互的短路熔断器：Redis <b>长时间慢故障</b>下，避免每个 claim/report 请求都阻塞至 {@code requestTimeout}(500ms) 才
 * fail-open。#782 把 requestTimeout 从 2s 降到 500ms 缓解了线程饥饿，但热路径（12000/min≈200/s）在持续慢故障下仍叠 500ms
 * 延迟；本熔断器在连续超时判定 Redis 不健康后，直接 fail-open 放行<b>不再发 Redis 命令</b>，把叠加延迟从 500ms 降到 ~0。
 *
 * <p><b>为何用 resilience4j 而非手写状态机</b>：#774（{@code DownstreamFallback}）已在生产用 R4J CircuitBreaker 承载
 * OPEN→HALF_OPEN→CLOSED 状态机，有先例、有 autoconfig 埋点、省去自研 CAS/半开探测并发控制。复用共享 {@link
 * CircuitBreakerRegistry}（{@code ObjectProvider}，不新建竞争 bean，遵守"禁覆盖 batch-common 基础设施 bean"红线）。
 *
 * <p><b>"连续 N 次失败才熔断"的表达</b>：R4J 是 failure-rate 语义而非原生连续计数。用 COUNT_BASED 滑动窗口 {@code
 * slidingWindowSize = minimumNumberOfCalls = consecutiveFailures}、{@code failureRateThreshold =
 * 100%} 精确表达——只有最近 N 次调用<b>全部</b>失败才 OPEN，窗口内任一次成功即打断连续计数，规避偶发抖动误熔断。
 *
 * <p><b>只把 Redis 故障计入失败</b>：{@code recordExceptions(RedisException, bucket4j TimeoutException)}——与
 * #782 兜住的两类完全一致。限流"拒绝"（{@code tryConsume} 正常返回 false）不抛异常，不计失败，不会误开熔断。R4J 记录失败后<b>原样上抛</b>原异常， 故
 * {@link TokenBucketRateLimiter} 现有 {@code catch(RedisException|TimeoutException)} fail-open
 * 分支语义不变。
 *
 * <p><b>fail-open 方向不变</b>：熔断 OPEN 时 {@link #call} 抛 {@link
 * CallNotPermittedException}，由调用方翻译成放行（不是拒绝）。
 *
 * <p><b>指标</b>（低基数，无 tenant/action tag）：{@code batch.ratelimit.redis.circuit.open}（0/1
 * gauge，1=熔断打开= 限流暂时形同虚设，与 #782 的 {@code batch.ratelimit.failopen.total} counter 一套告警）。R4J
 * autoconfig 的 {@code resilience4j.circuitbreaker.*} 另有细粒度埋点并存。
 */
@Slf4j
@Component
public class RedisRateLimitCircuitBreaker {

  /** R4J CircuitBreaker 实例名（进共享 registry + resilience4j.circuitbreaker.* 埋点的 name 维度）。 */
  static final String CIRCUIT_NAME = "ratelimit-redis";

  /** 熔断开态 gauge：1=OPEN（短路放行，限流暂失效），0=CLOSED/HALF_OPEN。低基数，无 tenant/action tag。 */
  static final String METRIC_CIRCUIT_OPEN = "batch.ratelimit.redis.circuit.open";

  private final boolean enabled;

  /**
   * 关闭短路熔断（{@code enabled=false}）时为 {@code null}，{@link #call} 退化为直发 Redis（保留 #782 纯 fail-open）。
   */
  private final CircuitBreaker circuitBreaker;

  @Autowired
  public RedisRateLimitCircuitBreaker(
      RateLimitProperties properties,
      ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider,
      MeterRegistry meterRegistry) {
    this(
        properties.getRedisCircuitBreaker(),
        // 优先复用 R4J autoconfig 的共享 registry（不新建竞争 bean）；窄上下文单测无 autoconfig 时退回内置默认 registry。
        circuitBreakerRegistryProvider.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
        meterRegistry);
  }

  /** 直连构造：单测用真实 {@link CircuitBreakerRegistry} 免去 {@code ObjectProvider} 桩。 */
  RedisRateLimitCircuitBreaker(
      RateLimitProperties.CircuitBreaker config,
      CircuitBreakerRegistry registry,
      MeterRegistry meterRegistry) {
    this.enabled = config.isEnabled();
    this.circuitBreaker =
        enabled ? registry.circuitBreaker(CIRCUIT_NAME, buildConfig(config)) : null;
    registerStateGauge(meterRegistry);
  }

  /** 单测便捷：关闭熔断（纯 passthrough，等价 #782 前行为），供不关心短路的 fail-open 测试复用。 */
  static RedisRateLimitCircuitBreaker disabled(MeterRegistry meterRegistry) {
    RateLimitProperties.CircuitBreaker config = new RateLimitProperties.CircuitBreaker();
    config.setEnabled(false);
    return new RedisRateLimitCircuitBreaker(
        config, CircuitBreakerRegistry.ofDefaults(), meterRegistry);
  }

  /**
   * 经熔断执行 Redis 限流调用。
   *
   * <ul>
   *   <li>CLOSED / HALF_OPEN：执行 {@code redisCall}；抛 {@link RedisException} / {@link
   *       TimeoutException} 时 计入失败并<b>原样上抛</b>（调用方 fail-open 分支照旧兜住）；正常返回（放行/拒绝）计成功。
   *   <li>OPEN：不执行 {@code redisCall}，抛 {@link CallNotPermittedException} 让调用方短路 fail-open（省掉
   *       requestTimeout 阻塞）。
   * </ul>
   *
   * <p>熔断关闭（{@code enabled=false}）时直发 {@code redisCall} 不经状态机。
   */
  public <T> T call(Supplier<T> redisCall) {
    if (!enabled || circuitBreaker == null) {
      return redisCall.get();
    }
    return circuitBreaker.executeSupplier(redisCall);
  }

  private static CircuitBreakerConfig buildConfig(RateLimitProperties.CircuitBreaker config) {
    int window = Math.max(1, config.getConsecutiveFailures());
    return CircuitBreakerConfig.custom()
        // COUNT_BASED + size=minCalls=window + 100% 阈值 = "连续 window 次全失败才 OPEN"（窗口内任一成功打断）。
        .slidingWindowType(SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(window)
        .minimumNumberOfCalls(window)
        .failureRateThreshold(100.0f)
        .waitDurationInOpenState(Duration.ofMillis(Math.max(1L, config.getOpenWindowMillis())))
        .permittedNumberOfCallsInHalfOpenState(Math.max(1, config.getHalfOpenProbes()))
        // 不后台自动转 HALF_OPEN：窗口到期后由下一个真实请求承载探测（该请求真发 Redis 命令），符合"放一个探测请求"语义。
        .automaticTransitionFromOpenToHalfOpenEnabled(false)
        // 只有 Redis 命令级故障 / bucket4j 超时计失败；限流"拒绝"正常返回 false 不计失败，不误熔断。
        .recordExceptions(RedisException.class, TimeoutException.class)
        .build();
  }

  private void registerStateGauge(MeterRegistry meterRegistry) {
    if (meterRegistry == null) {
      return;
    }
    Gauge.builder(METRIC_CIRCUIT_OPEN, this, RedisRateLimitCircuitBreaker::openStateSample)
        .description(
            "Rate-limiter Redis circuit breaker state (1=open → tryConsume short-circuits to"
                + " fail-open without hitting Redis, so quota is temporarily unenforced; 0=closed"
                + " or half-open probing).")
        .register(meterRegistry);
  }

  /** gauge 采样：OPEN / FORCED_OPEN → 1（短路放行中），其余（CLOSED / HALF_OPEN / DISABLED）→ 0。 */
  private int openStateSample() {
    if (circuitBreaker == null) {
      return 0;
    }
    CircuitBreaker.State state = circuitBreaker.getState();
    return (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN)
        ? 1
        : 0;
  }
}
