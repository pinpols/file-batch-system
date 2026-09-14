package io.github.pinpols.batch.orchestrator.application.service.capacity;

/** 单个 tenant/job/worker 维度的容量画像行。 */
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
    return new CapacityProfileRow(
        tenantId,
        jobCode,
        workerCode,
        workerGroup,
        nonNegative(instanceCount),
        nonNegative(taskCount),
        nonNegative(successCount),
        nonNegative(failureCount),
        nonNegative(totalDurationMs),
        nonNegative(wallClockDurationMs),
        nonNegative(avgDurationMs),
        nonNegative(p95DurationMs),
        nonNegative(totalFileBytes),
        nonNegative(processedRecords),
        round(recordsRate),
        round(mbRate));
  }

  private static long nonNegative(long value) {
    return Math.max(0L, value);
  }

  private static double round(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }
}
