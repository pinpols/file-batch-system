package com.example.batch.common.dto;

import java.time.Instant;
import java.util.List;

public record WorkerHeartbeatDto(
    String tenantId,
    String workerCode,
    String workerGroup,
    String status,
    String hostName,
    String hostIp,
    String processId,
    // SDK Phase 5 / SDK-P5-3 运行指纹:租户应用构建标识 + 链接的 SDK 库版本;仅 register 上报,heartbeat 不带(null)。
    String buildId,
    String sdkVersion,
    Instant heartbeatAt,
    List<String> capabilityTags,
    Integer currentLoad,
    // SDK Phase 3 M3.1 — register 时上报自定义 taskType 描述符;heartbeat 不带(null)。
    List<WorkerTaskTypeDescriptorDto> taskTypes) {
  public WorkerHeartbeatDto {
    Integer normalizedCurrentLoad = currentLoad;
    if (normalizedCurrentLoad == null || normalizedCurrentLoad < 0) {
      normalizedCurrentLoad = 0;
    }
    currentLoad = normalizedCurrentLoad;
  }
}
