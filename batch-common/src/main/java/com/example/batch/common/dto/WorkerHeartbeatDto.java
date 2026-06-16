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
    List<WorkerTaskTypeDescriptorDto> taskTypes,
    // 2026-06-03 docs/design/pipeline-stage-progress-display.md:流式 stage(IMPORT LOAD /
    // EXPORT GENERATE)行级进度上报。仅这两个 stage 在跑时非空,FE 据此显示计数器 + ETA。
    Long rowsProcessed,
    Long totalRowsHint,
    // SDK 协议门禁:worker 上报的 wire 协议 schema 主版本(如 "v1" / "v2");仅 register 带,heartbeat 不带(null)。
    // 缺字段=老 SDK / 非 SDK worker,平台按 legacy 接纳;present-but-unsupported(如 "v3")register 拒绝。
    // 校验逻辑见 SdkProtocolVersions;权威集合 = sdk-shared-constants.yaml schema_versions_supported。
    String protocolVersion) {
  public WorkerHeartbeatDto {
    Integer normalizedCurrentLoad = currentLoad;
    if (normalizedCurrentLoad == null || normalizedCurrentLoad < 0) {
      normalizedCurrentLoad = 0;
    }
    currentLoad = normalizedCurrentLoad;
  }
}
