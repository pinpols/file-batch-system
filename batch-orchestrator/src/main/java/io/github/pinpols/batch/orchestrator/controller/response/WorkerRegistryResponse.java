package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;
import lombok.Builder;

/** Worker 生命周期状态的稳定 HTTP 表示。 */
@Builder
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
    return WorkerRegistryResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .workerCode(entity.workerCode())
        .workerGroup(entity.workerGroup())
        .capabilityTags(entity.capabilityTags())
        .resourceTag(entity.resourceTag())
        .status(entity.status())
        .heartbeatAt(entity.heartbeatAt())
        .currentLoad(entity.currentLoad())
        .maxConcurrent(entity.maxConcurrent())
        .drainStartedAt(entity.drainStartedAt())
        .drainDeadlineAt(entity.drainDeadlineAt())
        .hostName(entity.hostName())
        .hostIp(entity.hostIp())
        .processId(entity.processId())
        .port(entity.port())
        .buildId(entity.buildId())
        .sdkVersion(entity.sdkVersion())
        .workerPoolCode(entity.workerPoolCode())
        .taskCapabilities(entity.taskCapabilities())
        .build();
  }
}
