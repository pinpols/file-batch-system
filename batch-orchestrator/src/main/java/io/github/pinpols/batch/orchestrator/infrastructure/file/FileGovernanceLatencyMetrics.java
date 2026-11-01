package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件治理延迟指标的固定输出契约。
 *
 * <p>Redis 缓存内部仍允许使用 Map，因为缓存数据需要兼容历史字段和 JSON 样本；但 Controller 和
 * 调度器只接收这个固定视图，避免把稳定字段名散落到业务代码中。样本行来自固定的 Mapper 投影，当前在缓存和响应边界使用 Map 保留既有字段形状。
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
    if (EmptyChecks.isEmpty(values)) {
      return empty(null);
    }
    Object tenantIdValue = values.get("tenantId");
    String tenantId = Objects.toString(tenantIdValue, null);
    long arrivalDelayViolations = number(values.get("arrivalDelayViolations"));
    long maxArrivalDelaySeconds = number(values.get("maxArrivalDelaySeconds"));
    long processingDelayViolations = number(values.get("processingDelayViolations"));
    long maxProcessingDelaySeconds = number(values.get("maxProcessingDelaySeconds"));
    List<Map<String, Object>> arrivalDelaySamples = samples(values.get("arrivalDelaySamples"));
    List<Map<String, Object>> processingDelaySamples =
        samples(values.get("processingDelaySamples"));
    return new FileGovernanceLatencyMetrics(
        tenantId,
        arrivalDelayViolations,
        maxArrivalDelaySeconds,
        processingDelayViolations,
        maxProcessingDelaySeconds,
        arrivalDelaySamples,
        processingDelaySamples);
  }

  public static FileGovernanceLatencyMetrics empty(String tenantId) {
    return new FileGovernanceLatencyMetrics(tenantId, 0L, 0L, 0L, 0L, List.of(), List.of());
  }

  private static long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (EmptyChecks.isNull(value)) {
      return 0L;
    }
    String numericText = String.valueOf(value);
    return Long.parseLong(numericText);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> samples(Object value) {
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }
}
