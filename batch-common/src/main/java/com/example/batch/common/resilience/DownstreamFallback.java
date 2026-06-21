package com.example.batch.common.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 下游调用降级模板 — 把"try / catch RestClientException / log / fallback"模式集中起来。
 *
 * <p>P1-B 第一步:替换散在各 {@code *ProxyService} 里的手写 try/catch,统一打 metrics + 统一日志格式 + 留 Resilience4j
 * 升级钩子(SB4 兼容性确认后只换本类内部实现即可)。
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
 *         "foo",                              // service 标签(进 metrics + log)
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
 *   <li>{@code downstream.call.total{service=<svc>, op=<op>, outcome=success|fallback}}
 *   <li>{@code downstream.call.fallback.total{service=<svc>, op=<op>, exception=<class>}}
 * </ul>
 */
@Slf4j
@Component
public class DownstreamFallback {

  private final MeterRegistry meterRegistry;

  public DownstreamFallback(ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.meterRegistry = meterRegistryProvider.getIfAvailable();
  }

  /**
   * 跑 {@code primary},失败(RestClientException)则跑 {@code fallback}。
   *
   * @param service 服务名,做 metrics 标签 + 日志 prefix(如 "trigger" / "orchestrator")
   * @param operation 操作名(如 "list" / "scheduler-status")
   * @param primary 主调用,通常是 RestClient.retrieve()
   * @param fallback 降级,接受异常返回默认值
   */
  public <T> T callOrFallback(
      String service, String operation, Supplier<T> primary, FallbackSupplier<T> fallback) {
    try {
      T result = primary.get();
      recordOutcome(service, operation, "success", null);
      return result;
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
   * <p>语义:跟直接调用相比,只多加了 metrics 上报 + 日志统一格式。
   */
  public <T> T callOrThrow(String service, String operation, Supplier<T> primary) {
    try {
      T result = primary.get();
      recordOutcome(service, operation, "success", null);
      return result;
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
