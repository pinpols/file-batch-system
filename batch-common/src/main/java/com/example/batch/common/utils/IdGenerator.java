package com.example.batch.common.utils;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 全局唯一 ID 生成工具类。 {@code newTraceId()} 优先取当前 OTel active span 的 traceId（让业务持久化字段与 OTel 自动 traceId
 * 一致），无 OTel 上下文时 fallback 到无连字符的 UUID； {@code newBusinessNo(prefix)} 生成带前缀、ISO 时间戳和随机后缀的业务编号，格式为
 * {@code prefix-yyyyMMddTHHmmssZ-xxxxxxxx}。
 *
 * <p>OTel 桥接详见 {@link OtelTraceContext} + {@code
 * docs/architecture/adr/ADR-013-distributed-tracing.md}。
 */
public final class IdGenerator {

  private IdGenerator() {}

  public static String newTraceId() {
    String otel = OtelTraceContext.currentTraceIdOrNull();
    if (otel != null) {
      return otel;
    }
    return UUID.randomUUID().toString().replace("-", "");
  }

  /**
   * ADR-014: per-partition CLAIM invocation id (never bridged from OTel — independent stale-worker
   * guard).
   */
  public static String newInvocationId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static String newBusinessNo(String prefix) {
    return prefix
        + "-"
        + DateTimeFormatter.ISO_INSTANT
            .format(BatchDateTimeSupport.utcNow())
            .replace(":", "")
            .replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }
}
