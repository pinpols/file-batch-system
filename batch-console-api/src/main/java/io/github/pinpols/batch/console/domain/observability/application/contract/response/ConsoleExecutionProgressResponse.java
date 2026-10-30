package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.console.domain.observability.view.dashboard.ExecutionProgressView;
import java.time.Instant;
import lombok.Builder;

/**
 * dashboard execution-progress 单行响应（列表元素）。
 *
 * <p>所有字段恒定输出（service 用 LinkedHashMap 显式 put，含 startedAt/finishedAt 的显式 null）→ 不加 {@code
 * NON_NULL}，保留历史 wire 的 null 键。{@code progressPercent} 为四舍五入的整数百分比。
 */
@Builder
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

  public static ConsoleExecutionProgressResponse from(ExecutionProgressView row) {
    if (row == null) {
      return null;
    }
    return ConsoleExecutionProgressResponse.builder()
        .id(row.id())
        .jobCode(row.jobCode())
        .instanceNo(row.instanceNo())
        .instanceStatus(row.instanceStatus())
        .expectedPartitions(row.expectedPartitions())
        .successPartitions(row.successPartitions())
        .failedPartitions(row.failedPartitions())
        .completedPartitions(row.completedPartitions())
        .progressPercent(row.progressPercent())
        .startedAt(row.startedAt())
        .finishedAt(row.finishedAt())
        .build();
  }
}
