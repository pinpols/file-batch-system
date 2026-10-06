package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import java.time.Instant;

/** Worker 注册记录的 Console 只读视图。 */
public record WorkerRegistryResponse(
    Long id,
    String tenantId,
    String workerCode,
    String workerGroup,
    String status,
    Instant heartbeatAt,
    Integer currentLoad,
    Integer maxConcurrent,
    Instant drainStartedAt,
    Instant drainDeadlineAt,
    /** Worker 实际监听 HTTP 端口；NULL=未上报（老 worker / 老 SDK / 非 web 上下文）。 */
    Integer port) {

  public static WorkerRegistryResponse from(WorkerRegistryEntity entity) {
    return new WorkerRegistryResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getWorkerCode(),
        entity.getWorkerGroup(),
        entity.getStatus(),
        entity.getHeartbeatAt(),
        entity.getCurrentLoad(),
        entity.getMaxConcurrent(),
        entity.getDrainStartedAt(),
        entity.getDrainDeadlineAt(),
        entity.getPort());
  }
}
