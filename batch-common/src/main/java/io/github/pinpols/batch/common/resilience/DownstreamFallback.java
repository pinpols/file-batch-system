package io.github.pinpols.batch.common.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 下游调用降级模板 — 把"try / catch RestClientException / log / fallback"模式集中起来,并由 resilience4j
 * CircuitBreaker 承载失败率阈值 / 滑动窗口 / OPEN→HALF_OPEN→CLOSED 状态机(spike Phase 2-B)。
 *
 * <p>P1-B 第一步:替换散在各 {@code *ProxyService} 里的手写 try/catch,统一打 metrics + 统一日志格式。内部实现从纯 try/catch 升级为
 * {@link CircuitBreaker#decorateSupplier};**方法签名与 {@code downstream.call.total} counter
 * 名保持不变**,调用方零改动。
 *
 * <p>使用方式:
 *
 * <pre>{@code
 * @Service
 * public class FooProxyService {
 *   private final DownstreamFallback fallback;
 *
 *   public List<Bar> listBars() {
 *     return fallback.callOrFallback(
 *         "foo",                              // service 标签(进 metrics + log + CircuitBreaker 实例名)
 *         "list",                             // operation 标签
 *         () -> client.get().retrieve().body(...),
 *         ex -> List.of()                      // fallback supplier(知错才返)
 *     );
 *   }
 * }
 * }</pre>
 *
 * <p>降级策略清单 + 责任归属:见 {@code docs/runbook/downstream-degradation.md}。
 *
 * <p>Metrics(给 Grafana / Alertmanager 用):
 *
 * <ul>
 *   <li>{@code downstream.call.total{service=<svc>, op=<op>,
 *       outcome=success|fallback|failure}}(本类,契约不变)
 *   <li>{@code resilience4j.circuitbreaker.*}(R4J autoconfig 自动埋点,与上面并存,新增)
 * </ul>
 *
 * <p>熔断触发后:{@code callOrFallback} 走 fallback(短路时把 {@link CallNotPermittedException} 包成 {@link
 * RestClientException} 传给 fallback,保持其入参契约);{@code callOrThrow} 抛出 {@link RestClientException}(调用方
 * catch {@code RestClientException} 语义不变)。
 */
@Slf4j
@Component
@EnableConfigurationProperties(DownstreamCircuitBreakerProperties.class)
public class DownstreamFallback {

  private final MeterRegistry meterRegistry;
  private final DownstreamCircuitBreakerProperties properties;
  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final CircuitBreakerConfig circuitBreakerConfig;

  public DownstreamFallback(
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider,
      DownstreamCircuitBreakerProperties properties) {
    this.meterRegistry = meterRegistryProvider.getIfAvailable();
    this.properties = properties;
    this.circuitBreakerConfig = buildConfig(properties);
    // 优先复用 R4J autoconfig 提供的共享 CircuitBreakerRegistry(不新建竞争 bean,遵守
    // "禁覆盖 batch-common 基础设施 bean" 红线);autoconfig 不在时(如窄上下文单测)退回内置默认 registry。
    this.circuitBreakerRegistry =
        circuitBreakerRegistryProvider.getIfAvailable(CircuitBreakerRegistry::ofDefaults);
  }

  private static CircuitBreakerConfig buildConfig(DownstreamCircuitBreakerProperties props) {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(Math.max(1, props.getSlidingWindowSize()))
        .minimumNumberOfCalls(Math.max(1, props.getMinimumNumberOfCalls()))
        .failureRateThreshold(props.getFailureRateThreshold())
        .waitDurationInOpenState(Duration.ofMillis(props.getWaitDurationInOpenStateMillis()))
        .permittedNumberOfCallsInHalfOpenState(Math.max(1, props.getPermittedCallsInHalfOpen()))
        // 只有 RestClient 层异常计入失败率;其它异常(如业务 IllegalState)不熔断、原样上抛。
        .recordExceptions(RestClientException.class)
        .build();
  }

  /**
   * 跑 {@code primary},失败(RestClientException)或熔断短路则跑 {@code fallback}。
   *
   * @param service 服务名,做 metrics 标签 + 日志 prefix + CircuitBreaker 实例名(如 "trigger" / "orchestrator")
   * @param operation 操作名(如 "list" / "scheduler-status")
   * @param primary 主调用,通常是 RestClient.retrieve()
   * @param fallback 降级,接受异常返回默认值
   */
  public <T> T callOrFallback(
      String service, String operation, Supplier<T> primary, FallbackSupplier<T> fallback) {
    try {
      T result = decorate(service, primary).get();
      recordOutcome(service, operation, "success", null);
      return result;
    } catch (CallNotPermittedException ex) {
      recordOutcome(service, operation, "fallback", ex.getClass().getSimpleName());
      log.warn(
          "downstream circuit open: service={}, op={}, cb={}", service, operation, ex.getMessage());
      return fallback.apply(new RestClientException("downstream circuit open: " + service, ex));
    } catch (RestClientException ex) {
      recordOutcome(service, operation, "fallback", ex.getClass().getSimpleName());
      log.warn(
          "downstream degraded: service={}, op={}, ex={}: {}",
          service,
          operation,
          ex.getClass().getSimpleName(),
          ex.getMessage());
      return fallback.apply(ex);
    }
  }

  /**
   * fail-fast 变体 — 不降级,只统计 + log 后原样上抛。给"业务必须 fail-fast"的写路径用。
   *
   * <p>语义:跟直接调用相比,只多加了 metrics 上报 + 日志统一格式 + 熔断。熔断短路时抛 {@link RestClientException}(调用方 catch {@code
   * RestClientException} 语义不变)。
   */
  public <T> T callOrThrow(String service, String operation, Supplier<T> primary) {
    try {
      T result = decorate(service, primary).get();
      recordOutcome(service, operation, "success", null);
      return result;
    } catch (CallNotPermittedException ex) {
      recordOutcome(service, operation, "failure", ex.getClass().getSimpleName());
      log.warn(
          "downstream circuit open (fail-fast): service={}, op={}, cb={}",
          service,
          operation,
          ex.getMessage());
      throw new RestClientException("downstream circuit open: " + service, ex);
    } catch (RestClientException ex) {
      recordOutcome(service, operation, "failure", ex.getClass().getSimpleName());
      log.warn(
          "downstream failed (fail-fast): service={}, op={}, ex={}: {}",
          service,
          operation,
          ex.getClass().getSimpleName(),
          ex.getMessage());
      throw ex;
    }
  }

  private <T> Supplier<T> decorate(String service, Supplier<T> primary) {
    if (!properties.isEnabled()) {
      return primary; // kill-switch:退回纯 try/catch,不经熔断状态机
    }
    CircuitBreaker circuitBreaker =
        circuitBreakerRegistry.circuitBreaker(service, circuitBreakerConfig);
    return CircuitBreaker.decorateSupplier(circuitBreaker, primary);
  }

  private void recordOutcome(String service, String operation, String outcome, String exception) {
    if (meterRegistry == null) {
      return; // 测试 / 无 micrometer 场景静默
    }
    Tags tags = Tags.of("service", service, "op", operation, "outcome", outcome);
    if (exception != null) {
      tags = tags.and("exception", exception);
    }
    meterRegistry.counter("downstream.call.total", tags).increment();
  }

  /** Fallback supplier — 接受异常,返回回退值。 */
  @FunctionalInterface
  public interface FallbackSupplier<T> {
    T apply(RestClientException ex);
  }
}
