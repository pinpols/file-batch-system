package io.github.pinpols.batch.console.domain.ops.web.response;

import io.github.pinpols.batch.console.domain.ops.entity.WorkerFingerprintSummaryRow;

/**
 * SDK Phase 5 / SDK-P5-3 (console Lane D):按 (buildId, sdkVersion) 聚合的 worker 数,供灰度切量可视化。
 *
 * <p>NULL 在 SQL 层 COALESCE 为字面量 {@code "(unknown)"}。
 */
public record WorkerFingerprintSummaryResponse(String buildId, String sdkVersion, long count) {

  public static WorkerFingerprintSummaryResponse from(WorkerFingerprintSummaryRow row) {
    return new WorkerFingerprintSummaryResponse(
        row.getBuildId(), row.getSdkVersion(), row.getCount() == null ? 0L : row.getCount());
  }
}
