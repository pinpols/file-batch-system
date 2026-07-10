package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按渠道维度({@code tenantId|channelType|channelCode})熔断,内部由 resilience4j {@link CircuitBreaker} per-key
 * 承载状态机。达到 {@link DispatchCircuitBreakerProperties#getFailureThreshold()} 次失败后在 {@link
 * DispatchCircuitBreakerProperties#getCooldownMillis()} 冷却期内熔断该渠道;冷却期满后转入 HALF_OPEN 做受限试探。
 *
 * <p>对外三方法门面({@link #allow}/{@link #recordSuccess}/{@link #recordFailure})+ 指标 {@link
 * #currentOpenCircuits()} 签名不变。语义映射:
 *
 * <ul>
 *   <li>{@code failureThreshold} → COUNT_BASED 窗口大小 + 最小调用数,失败率阈值 100%,等价"连续 N 次失败即熔断"。
 *   <li>{@code cooldownMillis} → {@code waitDurationInOpenState}。
 * </ul>
 *
 * <p><b>行为增强</b>:原手写实现冷却期一到即全放行(无半开态),此处补 HALF_OPEN 受限探测——冷却后仅放行少量试探调用 ({@code
 * permittedNumberOfCallsInHalfOpenState}),成功才闭合、失败立即重新熔断,避免半开瞬间被洪峰打垮。
 *
 * <p><b>指标绑定(#783 B3 覆盖缺口补齐)</b>:自持 registry 不接入 R4J autoconfig 的共享 registry,因此需要显式用 {@link
 * TaggedCircuitBreakerMetrics#ofCircuitBreakerRegistry(CircuitBreakerRegistry)} 绑到注入的 {@link
 * MeterRegistry},让 {@code resilience4j.circuitbreaker.state/calls/...} 对本类的 per-key breaker
 * 也产出,而不只是 {@code batch.dispatch.circuits.open} 聚合 gauge(见 {@code DispatchDeliveryMetrics})。
 *
 * <p><b>高基数权衡</b>:per-key breaker 以 {@code tenantId|channelType|channelCode} 为
 * tag,组合数理论上随渠道×租户增长。缓解依据 {@link #recordSuccess}既有的驱逐语义——CLOSED 且窗口内无失败的健康 breaker 会被 {@code
 * registry.remove} 逐出,TaggedCircuitBreakerMetrics 绑定的是 registry 本身,breaker 被移除后其 tagged meter
 * 也随之收回(resilience4j-micrometer 通过 registry 的 onEntryRemoved 事件联动)。因此稳态下驻留在 registry(进而暴露 per-key
 * 明细指标)的只是当前失败中/半开试探中/熔断中的渠道,数量与 batch.dispatch.circuits.open 同数量级、天然有界;只有健康渠道不会长期占位。仍建议运维侧对 R4J
 * per-key 明细指标设合理的 scrape/保留策略作为兜底,避免故障风暴瞬间 tenant×channel×code 组合激增。
 */
@Component
public class DispatchChannelCircuitBreaker {

  /** HALF_OPEN 允许的受限试探调用数(行为增强,原实现无半开态)。 */
  private static final int HALF_OPEN_PROBES = 3;

  /** onError 需要一个 Throwable 计入失败;渠道投递失败以布尔返回值表达,这里用一个无栈标记异常。 */
  private static final Throwable DISPATCH_FAILURE = new DispatchFailureSignal();

  private final DispatchCircuitBreakerProperties properties;
  private final CircuitBreakerRegistry registry;

  /**
   * 生产/Spring 装配路径:注入真实 {@link MeterRegistry},自持 registry 绑到 micrometer,per-key breaker 状态吐 {@code
   * resilience4j.circuitbreaker.*} 指标。
   */
  @Autowired
  public DispatchChannelCircuitBreaker(
      DispatchCircuitBreakerProperties properties, MeterRegistry meterRegistry) {
    this.properties = properties;
    this.registry = CircuitBreakerRegistry.of(buildConfig(properties));
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
  }

  /**
   * 纯单元测试便利构造器:不接 micrometer(内部用一次性 {@link SimpleMeterRegistry}),熔断行为与二参构造器完全等价。测试如需断言指标
   * 绑定,请显式用二参构造器传入自己的 {@link MeterRegistry}。
   */
  public DispatchChannelCircuitBreaker(DispatchCircuitBreakerProperties properties) {
    this(properties, new SimpleMeterRegistry());
  }

  private static CircuitBreakerConfig buildConfig(DispatchCircuitBreakerProperties properties) {
    int window = Math.max(1, properties.getFailureThreshold());
    return CircuitBreakerConfig.custom()
        .slidingWindowType(SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(window)
        .minimumNumberOfCalls(window)
        // 100% 阈值 + 窗口=阈值:窗口内全失败才熔断,等价原"连续 N 次失败"语义;
        // 期间出现一次成功即拉低失败率,不熔断(与原实现 recordSuccess 清零计数一致)。
        .failureRateThreshold(100.0f)
        .waitDurationInOpenState(Duration.ofMillis(properties.getCooldownMillis()))
        .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_PROBES)
        .build();
  }

  /**
   * 是否放行该渠道的一次投递。CLOSED → 放行;OPEN(冷却期内)→ 拒绝;冷却期满转 HALF_OPEN → 放行受限试探。
   *
   * <p>与 R4J 权限模型对齐:每次 {@code allow()} 取一个 permission,配对后续恰好一次 {@link #recordSuccess}/{@link
   * #recordFailure}(gateway 调用契约保证)。
   */
  public boolean allow(String key) {
    if (!properties.isEnabled()) {
      return true;
    }
    return registry.circuitBreaker(key).tryAcquirePermission();
  }

  public void recordSuccess(String key) {
    if (!properties.isEnabled()) {
      return;
    }
    CircuitBreaker circuitBreaker = registry.circuitBreaker(key);
    circuitBreaker.onSuccess(0L, TimeUnit.NANOSECONDS);
    // 有界化(对齐原 ConcurrentHashMap 在恢复时 remove 的语义):CLOSED 且窗口内无残留失败的健康 breaker
    // 可安全驱逐,避免高基数 tenant|channelType|channelCode 无界增长;下次调用会重建等价的新 CLOSED 实例。
    // HALF_OPEN 探测中不驱逐,以免打断受限试探。
    if (circuitBreaker.getState() == CircuitBreaker.State.CLOSED
        && circuitBreaker.getMetrics().getNumberOfFailedCalls() == 0) {
      registry.remove(key);
    }
  }

  public void recordFailure(String key) {
    if (!properties.isEnabled()) {
      return;
    }
    registry.circuitBreaker(key).onError(0L, TimeUnit.NANOSECONDS, DISPATCH_FAILURE);
  }

  /** 返回当前处于熔断(OPEN)状态的渠道数量。HALF_OPEN 不计入(已在试探恢复中)。 */
  public int currentOpenCircuits() {
    return (int)
        registry.getAllCircuitBreakers().stream()
            .filter(cb -> cb.getState() == CircuitBreaker.State.OPEN)
            .count();
  }

  /** 轻量标记异常:无消息、不填栈,仅用于把渠道投递失败喂给 R4J 的 onError。 */
  private static final class DispatchFailureSignal extends RuntimeException {
    private DispatchFailureSignal() {
      super("dispatch delivery failed", null, false, false);
    }
  }
}
