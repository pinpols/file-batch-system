package io.github.pinpols.batch.console.web.response.ops;

import java.time.Instant;
import java.util.List;

/** 单个租户在指定时间窗口内的容量画像。 */
public record CapacityProfileResponse(
    Instant generatedAt,
    String tenantId,
    CapacityProfileWindow window,
    String groupBy,
    String scope,
    List<CapacityProfileRow> rows,
    CapacityProfileTotals totals,
    CapacityProfileCoverage coverage) {

  public record CapacityProfileWindow(Instant from, Instant to) {}

  public record CapacityProfileRow(
      String tenantId,
      String jobCode,
      String workerCode,
      String workerGroup,
      long instanceCount,
      long taskCount,
      long successCount,
      long failureCount,
      long totalDurationMs,
      long avgDurationMs,
      long p95DurationMs,
      long totalFileBytes,
      long processedRecords,
      double recordsPerSecond,
      double mbPerSecond) {}

  public record CapacityProfileTotals(
      long instanceCount,
      long taskCount,
      long successCount,
      long failureCount,
      long totalDurationMs,
      long totalFileBytes,
      long processedRecords) {}

  public record CapacityProfileCoverage(
      String evidenceSource, List<String> knownGaps, List<String> rejectedScopes) {}
}
