package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

/**
 * 验证 OTel current span → 业务 trace_id 桥接：有 valid span context 时 IdGenerator.newTraceId() 返回 OTel
 * traceId；无 / invalid context 时 fallback UUID。
 *
 * <p>用 {@link Span#wrap(SpanContext)} 直接构造 SpanContext，避免依赖 opentelemetry-sdk-testing。
 */
class OtelTraceContextTest {

  private static final String VALID_TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String VALID_SPAN_ID = "0123456789abcdef";

  @Test
  void noActiveSpanReturnsNull() {
    assertThat(OtelTraceContext.currentTraceIdOrNull()).isNull();
  }

  @Test
  void newTraceIdFallsBackToUuidWhenNoActiveSpan() {
    String traceId = IdGenerator.newTraceId();
    assertThat(traceId).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  void validSpanContextExposesTraceId() {
    SpanContext valid =
        SpanContext.create(
            VALID_TRACE_ID, VALID_SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    Span span = Span.wrap(valid);
    try (Scope ignored = Context.current().with(span).makeCurrent()) {
      assertThat(OtelTraceContext.currentTraceIdOrNull()).isEqualTo(VALID_TRACE_ID);
      // IdGenerator 桥接到同一 traceId
      assertThat(IdGenerator.newTraceId()).isEqualTo(VALID_TRACE_ID);
    }
  }

  @Test
  void invalidSpanContextReturnsNull() {
    SpanContext invalid =
        SpanContext.create(
            "00000000000000000000000000000000",
            "0000000000000000",
            TraceFlags.getDefault(),
            TraceState.getDefault());
    Span invalidSpan = Span.wrap(invalid);
    try (Scope ignored = Context.current().with(invalidSpan).makeCurrent()) {
      assertThat(OtelTraceContext.currentTraceIdOrNull()).isNull();
      // fallback to UUID
      assertThat(IdGenerator.newTraceId()).hasSize(32).matches("[0-9a-f]+");
    }
  }
}
