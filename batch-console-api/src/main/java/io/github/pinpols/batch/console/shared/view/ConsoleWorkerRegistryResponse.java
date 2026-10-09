package io.github.pinpols.batch.console.shared.view;

import io.github.pinpols.batch.common.dto.WorkerTaskCapabilityDto;
import java.time.Instant;
import java.util.List;

public record ConsoleWorkerRegistryResponse(
    Long id,
    String tenantId,
    /** 存储中稳定的 Worker 注册编码。 */
    String workerCode,
    /** 调度与消费的分组键。 */
    String workerGroup,
    Object capabilityTags,
    String resourceTag,
    String status,
    Instant heartbeatAt,
    Integer currentLoad,
    Instant drainStartedAt,
    Instant drainDeadlineAt,
    /** Worker 实际监听 HTTP 端口；NULL=未上报（老 worker / 老 SDK / 非 web 上下文）。 */
    Integer port,
    List<WorkerTaskCapabilityDto> taskCapabilities) {

  public ConsoleWorkerRegistryResponse(
      Long id,
      String tenantId,
      String workerCode,
      String workerGroup,
      Object capabilityTags,
      String resourceTag,
      String status,
      Instant heartbeatAt,
      Integer currentLoad,
      Instant drainStartedAt,
      Instant drainDeadlineAt,
      Integer port) {
    this(
        id,
        tenantId,
        workerCode,
        workerGroup,
        capabilityTags,
        resourceTag,
        status,
        heartbeatAt,
        currentLoad,
        drainStartedAt,
        drainDeadlineAt,
        port,
        List.of());
  }
}
