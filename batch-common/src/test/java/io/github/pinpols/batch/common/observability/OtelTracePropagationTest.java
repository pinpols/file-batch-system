package io.github.pinpols.batch.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

class OtelTracePropagationTest {

  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String SPAN_ID = "0123456789abcdef";

  @Test
  void shouldCaptureCurrentW3cContext() {
    SpanContext spanContext =
        SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

    try (Scope ignored = Span.wrap(spanContext).makeCurrent()) {
      W3cTraceContext captured = OtelTracePropagation.captureCurrent();

      assertThat(captured).isNotNull();
      assertThat(captured.traceparent()).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
      assertThat(captured.tracestate()).isNull();
    }
  }

  @Test
  void shouldRestorePersistedRemoteParent() {
    W3cTraceContext persisted = new W3cTraceContext("00-" + TRACE_ID + "-" + SPAN_ID + "-01", null);

    try (Scope ignored = OtelTracePropagation.restore(persisted)) {
      assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
      assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(SPAN_ID);
      assertThat(Span.current().getSpanContext().isRemote()).isTrue();
    }
  }

  @Test
  void shouldKeepCurrentContextWhenPersistedContextIsInvalid() {
    SpanContext spanContext =
        SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault());

    try (Scope ignored = Span.wrap(spanContext).makeCurrent();
        Scope restored = OtelTracePropagation.restore(new W3cTraceContext("invalid", null))) {
      assertThat(Span.current().getSpanContext()).isEqualTo(spanContext);
    }
  }
}
