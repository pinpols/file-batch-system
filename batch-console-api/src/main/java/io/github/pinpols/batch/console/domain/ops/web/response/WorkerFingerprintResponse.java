package io.github.pinpols.batch.console.domain.ops.web.response;

import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerFingerprintRow;
import java.time.Instant;

/**
 * SDK Phase 5 / SDK-P5-3 (console Lane D):单个 worker 运行指纹响应。
 *
 * <p>{@code buildId} / {@code sdkVersion} 可空(非 SDK worker 不上报)。
 *
 * <p>{@code compatibility} 由后端按 {@code sdkVersion} 对照平台当前支持版本算出(SDK 运行时可见性 ①),供 console 标出旧 SDK /
 * 不支持协议的 worker。
 */
public record WorkerFingerprintResponse(
    Long id,
    String tenantId,
    String workerCode,
    String buildId,
    String processId,
    String sdkVersion,
    String status,
    Instant heartbeatAt,
    WorkerCompatibility compatibility) {

  public static WorkerFingerprintResponse from(
      WorkerFingerprintRow row, WorkerCompatibility compatibility) {
    return new WorkerFingerprintResponse(
        row.getId(),
        row.getTenantId(),
        row.getWorkerCode(),
        row.getBuildId(),
        row.getProcessId(),
        row.getSdkVersion(),
        row.getStatus(),
        row.getHeartbeatAt(),
        compatibility);
  }
}
