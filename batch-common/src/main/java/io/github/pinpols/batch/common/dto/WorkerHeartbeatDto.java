package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * Worker register / heartbeat / deactivate / updateStatus 的统一请求体。
 *
 * <p>字段绝大多数可空，且同一 schema 被四个端点复用：用 {@code @Builder(toBuilder = true)} 命名式构造，
 * 避免「18 个位置参数里塞一串 null」——那种写法一改字段顺序就静默错位，且完全读不出意图。
 * 与同包的 {@code LaunchRequest} 同一约定。Jackson 仍走 record 的规范构造器反序列化，wire 格式不变。
 */
@Builder(toBuilder = true)
public record WorkerHeartbeatDto(
    String tenantId,
    String workerCode,
    String workerGroup,
    String status,
    String hostName,
    String hostIp,
    String processId,
    // Worker 实际监听的 HTTP 端口（健康检查/指标）：内置 worker 上报 Spring 运行时绑定端口；
    // SDK 自托管 worker 可上报自己的监听端口；缺失=老 worker / 老 SDK / 非 web 上下文，平台存 NULL。
    Integer port,
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
    // 端口只接受有效监听端口；0（随机端口未绑定）与负值一律 normalize 为 null，不落库。
    if (EmptyChecks.isNotNull(port) && port <= 0) {
      port = null;
    }
  }
}
