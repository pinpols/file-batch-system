package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.Map;

/**
 * dashboard execution-progress 单行响应（列表元素）。
 *
 * <p>所有字段恒定输出（service 用 LinkedHashMap 显式 put，含 startedAt/finishedAt 的显式 null）→ 不加 {@code
 * NON_NULL}，保留历史 wire 的 null 键。{@code progressPercent} 为四舍五入的整数百分比。
 */
public record ConsoleExecutionProgressResponse(
    Long id,
    String jobCode,
    String instanceNo,
    String instanceStatus,
    Integer expectedPartitions,
    Integer successPartitions,
    Integer failedPartitions,
    Integer completedPartitions,
    Long progressPercent,
    Instant startedAt,
    Instant finishedAt) {

  public static ConsoleExecutionProgressResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleExecutionProgressResponse(
        longValue(row, "id"),
        stringValue(row, "jobCode"),
        stringValue(row, "instanceNo"),
        stringValue(row, "instanceStatus"),
        integerValue(row, "expectedPartitions"),
        integerValue(row, "successPartitions"),
        integerValue(row, "failedPartitions"),
        integerValue(row, "completedPartitions"),
        longValue(row, "progressPercent"),
        instantValue(row, "startedAt"),
        instantValue(row, "finishedAt"));
  }
}
