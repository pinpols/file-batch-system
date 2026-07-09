package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
 */
@Component
public class DispatchChannelCircuitBreaker {

  /** HALF_OPEN 允许的受限试探调用数(行为增强,原实现无半开态)。 */
  private static final int HALF_OPEN_PROBES = 3;

  /** onError 需要一个 Throwable 计入失败;渠道投递失败以布尔返回值表达,这里用一个无栈标记异常。 */
  private static final Throwable DISPATCH_FAILURE = new DispatchFailureSignal();

  private final DispatchCircuitBreakerProperties properties;
  private final CircuitBreakerRegistry registry;

  public DispatchChannelCircuitBreaker(DispatchCircuitBreakerProperties properties) {
    this.properties = properties;
    this.registry = CircuitBreakerRegistry.of(buildConfig(properties));
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
    registry.circuitBreaker(key).onSuccess(0L, TimeUnit.NANOSECONDS);
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
