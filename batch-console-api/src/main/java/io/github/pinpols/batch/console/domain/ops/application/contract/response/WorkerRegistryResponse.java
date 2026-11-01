package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import io.github.pinpols.batch.console.domain.ops.view.WorkerRegistryView;
import java.time.Instant;
import lombok.Builder;

/** Worker 注册记录的 Console 只读视图。 */
@Builder
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

  public static WorkerRegistryResponse from(WorkerRegistryView entity) {
    return WorkerRegistryResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .workerCode(entity.getWorkerCode())
        .workerGroup(entity.getWorkerGroup())
        .status(entity.getStatus())
        .heartbeatAt(entity.getHeartbeatAt())
        .currentLoad(entity.getCurrentLoad())
        .maxConcurrent(entity.getMaxConcurrent())
        .drainStartedAt(entity.getDrainStartedAt())
        .drainDeadlineAt(entity.getDrainDeadlineAt())
        .port(entity.getPort())
        .build();
  }
}
