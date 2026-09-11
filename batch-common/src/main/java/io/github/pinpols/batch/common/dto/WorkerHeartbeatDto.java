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
    // 2026-06-03 docs/design/pipeline-stage-progress-display.md:流式 stage(IMPORT LOAD /
    // EXPORT GENERATE)行级进度上报。仅这两个 stage 在跑时非空,FE 据此显示计数器 + ETA。
    Long rowsProcessed,
    Long totalRowsHint,
    // SDK 协议门禁:worker 上报的 wire 协议 schema 主版本(如 "v1" / "v2");仅 register 带,heartbeat 不带(null)。
    // 缺字段=老 SDK / 非 SDK worker,平台按 legacy 接纳;present-but-unsupported(如 "v3")register 拒绝。
    // 校验逻辑见 SdkProtocolVersions;权威集合 = sdk-shared-constants.yaml schema_versions_supported。
    String protocolVersion,
    // Worker 本地实际并发上限；内置 worker 在 register 时上报，控制面据此校准 selector 反压阈值。
    // 旧 SDK 缺字段时保持 null，由平台沿用已有值或数据库默认值。
    Integer maxConcurrent) {

  /**
   * 兼容旧调用方的 16 参数构造器。新增并发上报字段是可选 wire 字段，旧 SDK 和测试夹具无需同步升级。
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public WorkerHeartbeatDto(
      String tenantId,
      String workerCode,
      String workerGroup,
      String status,
      String hostName,
      String hostIp,
      String processId,
      String buildId,
      String sdkVersion,
      Instant heartbeatAt,
      List<String> capabilityTags,
      Integer currentLoad,
      List<WorkerTaskTypeDescriptorDto> taskTypes,
      Long rowsProcessed,
      Long totalRowsHint,
      String protocolVersion) {
    this(
        tenantId,
        workerCode,
        workerGroup,
        status,
        hostName,
        hostIp,
        processId,
        buildId,
        sdkVersion,
        heartbeatAt,
        capabilityTags,
        currentLoad,
        taskTypes,
        rowsProcessed,
        totalRowsHint,
        protocolVersion,
        null);
  }

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
