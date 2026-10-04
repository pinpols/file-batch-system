package io.github.pinpols.batch.common.dto;

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
    // SDK 协议门禁:worker 上报的 wire 协议 schema 主版本(如 "v1" / "v2");仅 register 带,heartbeat 不带(null)。
    // 缺字段=老 SDK / 非 SDK worker,平台按 legacy 接纳;present-but-unsupported(如 "v3")register 拒绝。
    // 校验逻辑见 SdkProtocolVersions;权威集合 = sdk-shared-constants.yaml schema_versions_supported。
    String protocolVersion,
    // Worker 本地实际并发上限；内置 worker 在 register 时上报，控制面据此校准 selector 反压阈值。
    // 旧 SDK 缺字段时保持 null，由平台沿用已有值或数据库默认值。
    Integer maxConcurrent,
    // 稳定路由池代码；workerCode 继续表示唯一运行实例。旧 SDK 不带时由平台回退为 workerCode。
    String workerPoolCode,
    // 并发任务阶段进度；缺省或空列表表示本轮没有活跃阶段。
    List<WorkerPipelineProgressDto> pipelineProgress) {

  public WorkerHeartbeatDto {
    Integer normalizedCurrentLoad = currentLoad;
    if (normalizedCurrentLoad == null || normalizedCurrentLoad < 0) {
      normalizedCurrentLoad = 0;
    }
    currentLoad = normalizedCurrentLoad;
    if (maxConcurrent != null && maxConcurrent <= 0) {
      maxConcurrent = null;
    }
  }
}
