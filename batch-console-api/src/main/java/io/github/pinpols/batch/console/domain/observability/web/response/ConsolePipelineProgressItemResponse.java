package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.Map;

/**
 * worker pipeline stage 行级进度（列表元素），透传自 orchestrator {@code
 * PipelineProgressInternalController.ProgressItem}，字段 1:1。
 *
 * <p>orchestrator 端为 record（无 {@code @JsonInclude}），{@code totalRowsHint} 为 null 时仍序列化显式 null 键 → 本
 * record 同样不加 {@code NON_NULL}，保持 wire 一致。
 */
public record ConsolePipelineProgressItemResponse(
    String workerCode, Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {

  public static ConsolePipelineProgressItemResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsolePipelineProgressItemResponse(
        stringValue(row, "workerCode"),
        longValue(row, "rowsProcessed"),
        longValue(row, "totalRowsHint"),
        instantValue(row, "heartbeatAt"));
  }
}
