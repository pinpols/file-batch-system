package io.github.pinpols.batch.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

class DownstreamFallbackTest {

  private MeterRegistry meterRegistry;
  private DownstreamFallback fallback;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    fallback = new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), defaultProps());
  }

  @Test
  void returnsPrimaryWhenSuccess() {
    String r = fallback.callOrFallback("svc", "op", () -> "value", ex -> "fallback");
    assertThat(r).isEqualTo("value");
    assertThat(
            meterRegistry
                .counter(
                    "downstream.call.total", "service", "svc", "op", "op", "outcome", "success")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void returnsFallbackWhenRestClientException() {
    String r =
        fallback.callOrFallback(
            "trigger",
            "list",
            () -> {
              throw new ResourceAccessException("downstream down");
            },
            ex -> "fallback");
    assertThat(r).isEqualTo("fallback");
    assertThat(
            meterRegistry
                .counter(
                    "downstream.call.total",
                    "service",
                    "trigger",
                    "op",
                    "list",
                    "outcome",
                    "fallback",
                    "exception",
                    "ResourceAccessException")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void propagatesNonRestClientException() {
    assertThatThrownBy(
            () ->
                fallback.callOrFallback(
                    "svc",
                    "op",
                    () -> {
                      throw new IllegalStateException("not a rest error");
                    },
                    ex -> "fallback"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void callOrThrowSucceeds() {
    List<Integer> r = fallback.callOrThrow("svc", "op", () -> List.of(1, 2));
    assertThat(r).hasSize(2);
    assertThat(
            meterRegistry
                .counter(
                    "downstream.call.total", "service", "svc", "op", "op", "outcome", "success")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void callOrThrowRecordsFailureAndRethrows() {
    assertThatThrownBy(
            () ->
                fallback.callOrThrow(
                    "trigger",
                    "pause",
                    () -> {
                      throw new ResourceAccessException("dead");
                    }))
        .isInstanceOf(ResourceAccessException.class);
    assertThat(
            meterRegistry
                .counter(
                    "downstream.call.total",
                    "service",
                    "trigger",
                    "op",
                    "pause",
                    "outcome",
                    "failure",
                    "exception",
                    "ResourceAccessException")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void worksWithoutMeterRegistry() {
    DownstreamFallback noMetrics =
        new DownstreamFallback(providerOf(null), emptyProvider(), defaultProps());
    String r = noMetrics.callOrFallback("svc", "op", () -> "ok", ex -> "fb");
    assertThat(r).isEqualTo("ok");
    // 不抛 NPE 即过
  }

  // ─── 熔断状态机(spike Phase 2-B 新增)────────────────────────────────────────

  @Test
  void opensAfterRepeatedFailuresThenShortCircuitsToFallbackWithoutCallingPrimary() {
    DownstreamFallback cb =
        new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), tunedProps());
    AtomicInteger primaryCalls = new AtomicInteger();

    // 4 次失败(minCalls=4, window=4, 100% > 50% 阈值)→ OPEN
    for (int i = 0; i < 4; i++) {
      String r =
          cb.callOrFallback(
              "flaky",
              "op",
              () -> {
                primaryCalls.incrementAndGet();
                throw new ResourceAccessException("boom");
              },
              ex -> "fb");
      assertThat(r).isEqualTo("fb");
    }
    assertThat(primaryCalls.get()).isEqualTo(4);

    // 现在 OPEN:下一次调用被短路,primary 不再被触碰,直接走 fallback
    String shortCircuited =
        cb.callOrFallback(
            "flaky",
            "op",
            () -> {
              primaryCalls.incrementAndGet();
              return "should-not-run";
            },
            ex -> "fb-open");
    assertThat(shortCircuited).isEqualTo("fb-open");
    assertThat(primaryCalls.get())
        .as("primary must not be invoked while circuit OPEN")
        .isEqualTo(4);
  }

  @Test
  void callOrThrowShortCircuitsWithRestClientExceptionWhenOpen() {
    DownstreamFallback cb =
        new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), tunedProps());
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(
              () ->
                  cb.callOrThrow(
                      "flaky2",
                      "op",
                      () -> {
                        throw new ResourceAccessException("boom");
                      }))
          .isInstanceOf(ResourceAccessException.class);
    }
    // OPEN:短路仍以 RestClientException 抛出(调用方 catch 语义不变)
    assertThatThrownBy(() -> cb.callOrThrow("flaky2", "op", () -> "x"))
        .isInstanceOf(org.springframework.web.client.RestClientException.class)
        .hasMessageContaining("circuit open");
  }

  @Test
  void recoversToClosedViaHalfOpenProbe() throws InterruptedException {
    DownstreamCircuitBreakerProperties props = tunedProps();
    props.setWaitDurationInOpenStateMillis(20L); // 短 wait-duration,便于测试 HALF_OPEN
    DownstreamFallback cb =
        new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), props);

    for (int i = 0; i < 4; i++) {
      cb.callOrFallback(
          "recover",
          "op",
          () -> {
            throw new ResourceAccessException("boom");
          },
          ex -> "fb");
    }
    Thread.sleep(60); // 越过 20ms wait-duration

    // HALF_OPEN 试探成功 → CLOSED;permittedCallsInHalfOpen=2,两次成功探测足以闭合
    for (int i = 0; i < 2; i++) {
      String r = cb.callOrFallback("recover", "op", () -> "ok", ex -> "fb");
      assertThat(r).isEqualTo("ok");
    }
    // 已闭合,后续正常放行
    assertThat(cb.callOrFallback("recover", "op", () -> "ok-again", ex -> "fb"))
        .isEqualTo("ok-again");
  }

  @Test
  void disabledKillSwitchBypassesCircuitBreaker() {
    DownstreamCircuitBreakerProperties props = tunedProps();
    props.setEnabled(false);
    DownstreamFallback cb =
        new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), props);
    AtomicInteger primaryCalls = new AtomicInteger();

    // 即使连续失败远超阈值,禁用时永不熔断:primary 每次都被调用
    for (int i = 0; i < 10; i++) {
      cb.callOrFallback(
          "off",
          "op",
          () -> {
            primaryCalls.incrementAndGet();
            throw new ResourceAccessException("boom");
          },
          ex -> "fb");
    }
    assertThat(primaryCalls.get()).isEqualTo(10);
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  private static DownstreamCircuitBreakerProperties defaultProps() {
    return new DownstreamCircuitBreakerProperties();
  }

  /** 小窗口 + 低最小调用数,便于在少量失败内触发 OPEN。 */
  private static DownstreamCircuitBreakerProperties tunedProps() {
    DownstreamCircuitBreakerProperties props = new DownstreamCircuitBreakerProperties();
    props.setSlidingWindowSize(4);
    props.setMinimumNumberOfCalls(4);
    props.setFailureRateThreshold(50.0f);
    props.setWaitDurationInOpenStateMillis(30_000L);
    props.setPermittedCallsInHalfOpen(2);
    return props;
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> mock = org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(mock.getIfAvailable()).thenReturn(value);
    return mock;
  }

  /** 模拟 R4J autoconfig 不在场:getIfAvailable(Supplier) 回退到内置默认 registry。 */
  @SuppressWarnings("unchecked")
  private static ObjectProvider<CircuitBreakerRegistry> emptyProvider() {
    ObjectProvider<CircuitBreakerRegistry> mock = org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(mock.getIfAvailable(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv ->
                ((java.util.function.Supplier<CircuitBreakerRegistry>) inv.getArgument(0)).get());
    return mock;
  }
}
