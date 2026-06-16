package com.example.batch.common.utils;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

  /** ADR-014:每个分区的 CLAIM invocation id(绝不从 OTel 桥接而来 —— 独立的 stale-worker 守护)。 */
  public static String newInvocationId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static String newBusinessNo(String prefix) {
    return prefix + "-" + compactUtcNow() + "-" + shortRandomSuffix();
  }

  /**
   * 批量场景:同一事务/同一批次内生成 N 个业务号,共享 timestamp,后缀按行号唯一化。
   *
   * <p>典型用法:partition 拆分时同事务内 N 个分片号 → 日志聚合 + DB 排序紧凑。
   *
   * @param prefix 业务号前缀
   * @param count 数量,必须 ≥ 1
   * @return 长度 {@code count} 的不可变 List,顺序与下标一致
   */
  public static List<String> newBusinessNoBatch(String prefix, int count) {
    if (count <= 0) {
      throw new IllegalArgumentException("newBusinessNoBatch count must be >= 1, got " + count);
    }
    String ts = compactUtcNow();
    List<String> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ids.add(prefix + "-" + ts + "-" + shortRandomSuffix());
    }
    return List.copyOf(ids);
  }

  /**
   * 统一幂等键(Idempotency-Key)生成 — Controller 不必手写。
   *
   * <p>格式 {@code idem-<32-hex-uuid>};接 HTTP Header {@code Idempotency-Key} / RFC draft 风格,
   * 长度可控,前缀便于日志检索 + grep。
   */
  public static String newIdempotencyKey() {
    return "idem-" + UUID.randomUUID().toString().replace("-", "");
  }

  private static final DateTimeFormatter COMPACT_UTC =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  /** {@code yyyyMMddTHHmmssZ} — 一次 format,免 ISO_INSTANT 再 replace 的字符串遍历开销。 */
  private static String compactUtcNow() {
    return COMPACT_UTC.format(BatchDateTimeSupport.utcNow());
  }

  /** 8 位 16 进制随机后缀。 */
  private static String shortRandomSuffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
