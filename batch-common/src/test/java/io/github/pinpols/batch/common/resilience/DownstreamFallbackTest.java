package io.github.pinpols.batch.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
    fallback = new DownstreamFallback(providerOf(meterRegistry));
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
    DownstreamFallback noMetrics = new DownstreamFallback(providerOf(null));
    String r = noMetrics.callOrFallback("svc", "op", () -> "ok", ex -> "fb");
    assertThat(r).isEqualTo("ok");
    // 不抛 NPE 即过
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> mock = org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(mock.getIfAvailable()).thenReturn(value);
    return mock;
  }
}
