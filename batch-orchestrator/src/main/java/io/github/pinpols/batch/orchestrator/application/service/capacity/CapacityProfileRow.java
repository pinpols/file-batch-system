package io.github.pinpols.batch.orchestrator.application.service.capacity;

import lombok.Builder;

/** 单个 tenant/job/worker 维度的容量画像行。 */
@Builder(toBuilder = true)
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
    long wallClockDurationMs,
    long avgDurationMs,
    long p95DurationMs,
    long totalFileBytes,
    long processedRecords,
    double recordsPerSecond,
    double mbPerSecond) {

  public CapacityProfileRow withRates() {
    long throughputDurationMs = wallClockDurationMs > 0 ? wallClockDurationMs : totalDurationMs;
    double seconds = Math.max(throughputDurationMs, 0L) / 1000.0d;
    double recordsRate = seconds <= 0.0d ? 0.0d : processedRecords / seconds;
    double mbRate = seconds <= 0.0d ? 0.0d : (totalFileBytes / 1024.0d / 1024.0d) / seconds;
    return toBuilder()
        .instanceCount(nonNegative(instanceCount))
        .taskCount(nonNegative(taskCount))
        .successCount(nonNegative(successCount))
        .failureCount(nonNegative(failureCount))
        .totalDurationMs(nonNegative(totalDurationMs))
        .wallClockDurationMs(nonNegative(wallClockDurationMs))
        .avgDurationMs(nonNegative(avgDurationMs))
        .p95DurationMs(nonNegative(p95DurationMs))
        .totalFileBytes(nonNegative(totalFileBytes))
        .processedRecords(nonNegative(processedRecords))
        .recordsPerSecond(round(recordsRate))
        .mbPerSecond(round(mbRate))
        .build();
  }

  private static long nonNegative(long value) {
    return Math.max(0L, value);
  }

  private static double round(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }
}
