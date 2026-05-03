package com.example.batch.common.utils;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * OTel TraceContext 桥接工具。
 *
 * <p>取当前线程 OTel active span 的 traceId（32 hex chars，16 bytes），用于让业务持久化字段 {@code trace_id} 与 OTel
 * 自动生成的 traceId 保持一致 — 排障时一个 traceId 串起 Tempo/Jaeger timeline + 业务表 SQL 历史。
 *
 * <p>Spring Boot 4.x + Micrometer Tracing OTel bridge 在 HTTP/Kafka/JDBC 等自动 instrument 入口 自动建立 OTel
 * current span，本工具读取它即可。无 OTel 上下文（测试 / 内部触发 / disabled tracing）时返回 null， 调用方应 fallback 到原生成逻辑。
 *
 * <p>详见 {@code docs/architecture/adr/ADR-013-distributed-tracing.md}。
 */
public final class OtelTraceContext {

  /** OTel 全 0 traceId 表示无效 span (TraceId.getInvalid())，按 null 处理。 */
  private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

  private OtelTraceContext() {}

  /**
   * 取当前 OTel active span 的 traceId（32 hex chars）。
   *
   * @return 有效 traceId，或 null（无 active span / span context invalid / 全 0）
   */
  public static String currentTraceIdOrNull() {
    Span current = Span.current();
    if (current == null) {
      return null;
    }
    SpanContext ctx = current.getSpanContext();
    if (!ctx.isValid()) {
      return null;
    }
    String traceId = ctx.getTraceId();
    if (traceId == null || traceId.isEmpty() || INVALID_TRACE_ID.equals(traceId)) {
      return null;
    }
    return traceId;
  }
}
