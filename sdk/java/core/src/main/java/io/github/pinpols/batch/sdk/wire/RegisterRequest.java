package io.github.pinpols.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.sdk.task.SdkTaskTypeDescriptor;
import java.time.Instant;
import java.util.List;

/**
 * SDK wire DTO — {@code POST /internal/workers/register} 请求体。
 *
 * <p>对齐平台 {@code io.github.pinpols.batch.common.dto.WorkerHeartbeatDto}。字段集是协议契约,任何字段重命名 / 删除 /
 * 新增必须同时:
 *
 * <ol>
 *   <li>调整平台 {@code WorkerHeartbeatDto}
 *   <li>调整本 record(同 PR)
 *   <li>同步 {@code docs/api/orchestrator-internal.openapi.yaml}
 *   <li>{@code SdkWireContractTest}(batch-orchestrator) 重新跑过
 * </ol>
 *
 * <p>Phase 0 ({@code docs/plans/sdk-roadmap-2026-h2.md} §2)— SDK 协议演进基础。
 *
 * <p>反序列化 {@link JsonIgnoreProperties}{@code (ignoreUnknown=true)} 容忍平台未来新增字段,SDK 不 break。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(
    String tenantId,
    String workerCode,
    String workerGroup,
    String status,
    String hostName,
    String hostIp,
    String processId,
    // SDK Phase 5 / SDK-P5-3 运行指纹:租户应用构建标识 + 链接的 SDK 库版本。
    String buildId,
    String sdkVersion,
    Instant heartbeatAt,
    List<String> capabilityTags,
    Integer currentLoad,
    // SDK Phase 3 M3.1 — 自定义 taskType 描述符;heartbeat 不带,仅 register 上报。
    List<SdkTaskTypeDescriptor> taskTypes,
    // 本 SDK 实现的 wire 协议 schema 主版本(register 准入门禁用);平台不支持则拒绝注册。
    // 值见 {@link #CURRENT_PROTOCOL_VERSION};与平台 sdk-shared-constants.yaml schema_versions_supported
    // 对齐。
    String protocolVersion) {

  /**
   * 本 SDK 当前声明的 wire 协议 schema 主版本(register 上报)。取平台支持集合 {@code {v1, v2}} 的最高版本 —— SDK 同时兼容 v1/v2
   * 入站派单,注册时向平台声明可工作于 v2。平台不认此版本(如平台仅 v1)则 register 拒绝。改动须同步 {@code
   * docs/api/sdk-shared-constants.yaml}。
   */
  public static final String CURRENT_PROTOCOL_VERSION = "v2";
}
