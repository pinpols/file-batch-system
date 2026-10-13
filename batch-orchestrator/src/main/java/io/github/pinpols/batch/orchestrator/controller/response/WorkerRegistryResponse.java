package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;

/** Worker 生命周期状态的稳定 HTTP 表示。 */
public record WorkerRegistryResponse(
    Long id,
    String tenantId,
    String workerCode,
    String workerGroup,
    JsonbString capabilityTags,
    String resourceTag,
    String status,
    Instant heartbeatAt,
    Integer currentLoad,
    Integer maxConcurrent,
    Instant drainStartedAt,
    Instant drainDeadlineAt,
    String hostName,
    String hostIp,
    String processId,
    Integer port,
    String buildId,
    String sdkVersion,
    String workerPoolCode,
    JsonbString taskCapabilities) {

  public static WorkerRegistryResponse from(WorkerRegistryEntity entity) {
    return new WorkerRegistryResponse(
        entity.id(),
        entity.tenantId(),
        entity.workerCode(),
        entity.workerGroup(),
        entity.capabilityTags(),
        entity.resourceTag(),
        entity.status(),
        entity.heartbeatAt(),
        entity.currentLoad(),
        entity.maxConcurrent(),
        entity.drainStartedAt(),
        entity.drainDeadlineAt(),
        entity.hostName(),
        entity.hostIp(),
        entity.processId(),
        entity.port(),
        entity.buildId(),
        entity.sdkVersion(),
        entity.workerPoolCode(),
        entity.taskCapabilities());
  }
}
