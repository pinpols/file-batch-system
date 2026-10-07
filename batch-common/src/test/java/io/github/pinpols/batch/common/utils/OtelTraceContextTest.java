package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 OTel current span → 业务 trace_id 桥接：有 valid span context 时 IdGenerator.newTraceId() 返回 OTel
 * traceId；无 / invalid context 时 fallback UUID。
 *
 * <p>用 {@link Span#wrap(SpanContext)} 直接构造 SpanContext，避免依赖 opentelemetry-sdk-testing。
 */
@DisplayName("链路上下文桥接: 上游追踪标识的读取与无上下文时的追踪号回退")
class OtelTraceContextTest {

  private static final String VALID_TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String VALID_SPAN_ID = "0123456789abcdef";

  @Test
  @DisplayName("无活动链路上下文: 当前追踪标识返回空")
  void shouldReturnNull_whenNoActiveSpan() {
    assertThat(OtelTraceContext.currentTraceIdOrNull()).isNull();
  }

  @Test
  @DisplayName("无活动链路上下文: 业务追踪号回退为随机生成值")
  void shouldFallBackToGeneratedId_whenNoActiveSpan() {
    String traceId = IdGenerator.newTraceId();
    assertThat(traceId).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  @DisplayName("有效链路上下文: 当前追踪标识与上游一致, 业务追踪号同步桥接")
  void shouldExposeUpstreamTraceId_whenSpanContextIsValid() {
    SpanContext valid = SpanContext.create(
        VALID_TRACE_ID, VALID_SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    Span span = Span.wrap(valid);
    try (Scope ignored = Context.current().with(span).makeCurrent()) {
      assertThat(OtelTraceContext.currentTraceIdOrNull()).isEqualTo(VALID_TRACE_ID);
      // IdGenerator 桥接到同一 traceId
      assertThat(IdGenerator.newTraceId()).isEqualTo(VALID_TRACE_ID);
    }
  }

  @Test
  @DisplayName("无效链路上下文: 当前追踪标识返回空, 业务追踪号回退为随机值")
  void shouldReturnNullAndFallBack_whenSpanContextIsInvalid() {
    SpanContext invalid = SpanContext.create(
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
