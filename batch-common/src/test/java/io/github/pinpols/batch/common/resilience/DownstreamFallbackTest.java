package io.github.pinpols.batch.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("下游调用降级与熔断:成功与失败的回退路径,熔断状态机以及开关旁路")
class DownstreamFallbackTest {

  private MeterRegistry meterRegistry;
  private DownstreamFallback fallback;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    fallback = new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), defaultProps());
  }

  @Test
  @DisplayName("主调用成功:返回主结果并计一次成功计数")
  void shouldReturnPrimaryResult_whenCallSucceeds() {
    String r = fallback.callOrFallback("svc", "op", () -> "value", ex -> "fallback");
    assertThat(r).isEqualTo("value");
    assertThat(meterRegistry
            .counter("downstream.call.total", "service", "svc", "op", "op", "outcome", "success")
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("下游访问异常:返回回退值并按异常类型打点回退计数")
  void shouldReturnFallback_whenRestClientExceptionThrown() {
    String r = fallback.callOrFallback(
        "trigger",
        "list",
        () -> {
          throw new ResourceAccessException("downstream down");
        },
        ex -> "fallback");
    assertThat(r).isEqualTo("fallback");
    assertThat(meterRegistry
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
  @DisplayName("业务异常不属于可降级类型:原样向上抛出,不静默吞掉")
  void shouldPropagateException_whenNotRestClientFailure() {
    assertThatThrownBy(() -> fallback.callOrFallback(
            "svc",
            "op",
            () -> {
              throw new IllegalStateException("not a rest error");
            },
            ex -> "fallback"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("只抛不降级调用成功:返回结果并计一次成功计数")
  void shouldReturnResult_whenCallOrThrowSucceeds() {
    List<Integer> r = fallback.callOrThrow("svc", "op", () -> List.of(1, 2));
    assertThat(r).hasSize(2);
    assertThat(meterRegistry
            .counter("downstream.call.total", "service", "svc", "op", "op", "outcome", "success")
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("只抛不降级调用失败:记录失败计数后原异常继续抛出")
  void shouldRecordFailureAndRethrow_whenCallOrThrowFails() {
    assertThatThrownBy(() -> fallback.callOrThrow("trigger", "pause", () -> {
          throw new ResourceAccessException("dead");
        }))
        .isInstanceOf(ResourceAccessException.class);
    assertThat(meterRegistry
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
  @DisplayName("指标注册表缺失时仍正常返回主结果,不抛空指针异常")
  void shouldNotFail_whenMeterRegistryAbsent() {
    DownstreamFallback noMetrics =
        new DownstreamFallback(providerOf(null), emptyProvider(), defaultProps());
    String r = noMetrics.callOrFallback("svc", "op", () -> "ok", ex -> "fb");
    assertThat(r).isEqualTo("ok");
    // 不抛 NPE 即过
  }

  // ─── 熔断状态机(spike Phase 2-B 新增)────────────────────────────────────────

  @Test
  @DisplayName("连续失败达阈值后熔断打开:后续调用直接走回退且主逻辑不再被调用")
  void shouldOpenThenShortCircuit_whenFailuresReachThreshold() {
    DownstreamFallback cb =
        new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), tunedProps());
    AtomicInteger primaryCalls = new AtomicInteger();

    // 4 次失败(minCalls=4, window=4, 100% > 50% 阈值)→ OPEN
    for (int i = 0; i < 4; i++) {
      String r = cb.callOrFallback(
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
    String shortCircuited = cb.callOrFallback(
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
  @DisplayName("熔断打开时只抛不降级调用:仍以下游异常短路抛出,调用方捕获语义不变")
  void shouldThrowRestClientException_whenOpenAndCallOrThrow() {
    DownstreamFallback cb =
        new DownstreamFallback(providerOf(meterRegistry), emptyProvider(), tunedProps());
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> cb.callOrThrow("flaky2", "op", () -> {
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
  @DisplayName("等待窗口过后半开试探成功:熔断闭合,后续调用正常放行")
  void shouldRecoverToClosed_whenHalfOpenProbeSucceeds() throws InterruptedException {
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
  @DisplayName("熔断开关关闭时:即使持续失败也永不熔断,主逻辑每次都被调用")
  void shouldBypassCircuitBreaker_whenDisabled() {
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
    ObjectProvider<T> mock = mock(ObjectProvider.class);
    when(mock.getIfAvailable()).thenReturn(value);
    return mock;
  }

  /** 模拟 R4J autoconfig 不在场:getIfAvailable(Supplier) 回退到内置默认 registry。 */
  @SuppressWarnings("unchecked")
  private static ObjectProvider<CircuitBreakerRegistry> emptyProvider() {
    ObjectProvider<CircuitBreakerRegistry> mock = mock(ObjectProvider.class);
    when(mock.getIfAvailable(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv ->
            ((java.util.function.Supplier<CircuitBreakerRegistry>) inv.getArgument(0)).get());
    return mock;
  }
}
