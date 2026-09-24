package io.github.pinpols.batch.common.observability;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** 在持久化异步边界捕获和恢复最小 W3C 链路上下文。 */
public final class OtelTracePropagation {

  private static final String TRACE_PARENT = "traceparent";
  private static final String TRACE_STATE = "tracestate";
  private static final W3CTraceContextPropagator PROPAGATOR =
      W3CTraceContextPropagator.getInstance();
  private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
    @Override
    public Iterable<String> keys(Map<String, String> carrier) {
      if (carrier == null) { // empty-check: allow - Sonar S2259
        return List.of();
      }
      return carrier.keySet();
    }

    @Override
    public String get(@Nullable Map<String, String> carrier, String key) {
      return carrier == null ? null : carrier.get(key); // empty-check: allow - Sonar S2259
    }
  };

  private OtelTracePropagation() {}

  /** 未启用链路追踪或当前没有有效 span 时返回 null。 */
  @Nullable
  public static W3cTraceContext captureCurrent() {
    if (!Span.current().getSpanContext().isValid()) {
      return null;
    }
    Map<String, String> carrier = new LinkedHashMap<>(2);
    PROPAGATOR.inject(Context.current(), carrier, Map::put);
    String traceparent = carrier.get(TRACE_PARENT);
    if (EmptyChecks.isBlank(traceparent)) {
      return null;
    }
    return new W3cTraceContext(traceparent, carrier.get(TRACE_STATE));
  }

  /**
   * 在同步调用 Kafka send 期间恢复已持久化的远端父上下文。
   *
   * <p>载荷缺失或无效时保持当前上下文不变，避免覆盖已有的有效链路。
   */
  public static Scope restore(@Nullable W3cTraceContext traceContext) {
    if (traceContext == null || EmptyChecks.isBlank(traceContext.traceparent())) {
      return () -> {};
    }
    Map<String, String> carrier = new LinkedHashMap<>(2);
    carrier.put(TRACE_PARENT, traceContext.traceparent());
    if (EmptyChecks.isNotBlank(traceContext.tracestate())) {
      carrier.put(TRACE_STATE, traceContext.tracestate());
    }
    Context extracted = PROPAGATOR.extract(Context.root(), carrier, GETTER);
    if (!Span.fromContext(extracted).getSpanContext().isValid()) {
      return () -> {};
    }
    return extracted.makeCurrent();
  }
}
