package io.github.pinpols.batch.orchestrator.infrastructure.file;

import java.util.List;
import java.util.Map;

/**
 * 文件治理延迟指标的固定输出契约。
 *
 * <p>Redis 缓存内部仍允许使用 Map，因为缓存数据需要兼容历史字段和 JSON 样本；但 Controller 和
 * 调度器只接收这个固定视图，避免把稳定字段名散落到业务代码中。样本行本身来自动态 SQL 诊断结果，继续保留 Map。
 */
public record FileGovernanceLatencyMetrics(
    String tenantId,
    long arrivalDelayViolations,
    long maxArrivalDelaySeconds,
    long processingDelayViolations,
    long maxProcessingDelaySeconds,
    List<Map<String, Object>> arrivalDelaySamples,
    List<Map<String, Object>> processingDelaySamples) {

  public static FileGovernanceLatencyMetrics from(Map<String, Object> values) {
    if (values == null || values.isEmpty()) {
      return empty(null);
    }
    return new FileGovernanceLatencyMetrics(
        text(values.get("tenantId")),
        number(values.get("arrivalDelayViolations")),
        number(values.get("maxArrivalDelaySeconds")),
        number(values.get("processingDelayViolations")),
        number(values.get("maxProcessingDelaySeconds")),
        samples(values.get("arrivalDelaySamples")),
        samples(values.get("processingDelaySamples")));
  }

  public static FileGovernanceLatencyMetrics empty(String tenantId) {
    return new FileGovernanceLatencyMetrics(tenantId, 0L, 0L, 0L, 0L, List.of(), List.of());
  }

  private static long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return value == null ? 0L : Long.parseLong(String.valueOf(value));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> samples(Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
