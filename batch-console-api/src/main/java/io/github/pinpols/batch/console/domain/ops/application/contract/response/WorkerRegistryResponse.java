package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import java.time.Instant;

/** Public console view of a registered worker. */
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
    Instant drainDeadlineAt) {

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
        entity.getDrainDeadlineAt());
  }
}
