package io.github.pinpols.batch.orchestrator.application.service.capacity;

import java.time.Instant;
import java.util.List;

/** P2 cost profile 只读报告。 */
public record CapacityProfileReport(
    Instant generatedAt,
    String tenantId,
    CapacityProfileWindow window,
    CapacityProfileGroupBy groupBy,
    String scope,
    List<CapacityProfileRow> rows,
    CapacityProfileTotals totals,
    CapacityProfileCoverage coverage) {

  public record CapacityProfileWindow(Instant from, Instant to) {}

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
