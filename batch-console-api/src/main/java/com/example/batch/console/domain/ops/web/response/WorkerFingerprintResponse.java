package com.example.batch.console.domain.ops.web.response;

import com.example.batch.console.domain.ops.entity.WorkerFingerprintRow;
import java.time.Instant;

/**
 * SDK Phase 5 / SDK-P5-3 (console Lane D):单个 worker 运行指纹响应。
 *
 * <p>{@code buildId} / {@code sdkVersion} 可空(非 SDK worker 不上报)。
 */
public record WorkerFingerprintResponse(
    Long id,
    String tenantId,
    String workerCode,
    String buildId,
    String processId,
    String sdkVersion,
    String status,
    Instant heartbeatAt) {

  public static WorkerFingerprintResponse from(WorkerFingerprintRow row) {
    return new WorkerFingerprintResponse(
        row.getId(),
        row.getTenantId(),
        row.getWorkerCode(),
        row.getBuildId(),
        row.getProcessId(),
        row.getSdkVersion(),
        row.getStatus(),
        row.getHeartbeatAt());
  }
}
