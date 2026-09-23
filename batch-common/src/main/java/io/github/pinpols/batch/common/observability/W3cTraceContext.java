package io.github.pinpols.batch.common.observability;

/**
 * 随异步消息信封持久化的最小 W3C 链路上下文。
 *
 * <p>这里只承载传输层元数据，不是业务标识。刻意不保存 baggage，避免把用户或安全属性写入 Outbox 载荷。
 */
public record W3cTraceContext(String traceparent, String tracestate) {}
