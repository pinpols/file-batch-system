package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * SDK wire DTO — {@code POST /internal/workers/{workerCode}/heartbeat} 请求体。
 *
 * <p>共用平台 {@code com.example.batch.common.dto.WorkerHeartbeatDto} schema(register / heartbeat /
 * deactivate / updateStatus 路径同一 schema)。SDK 侧拆成独立 record 是为了:
 *
 * <ul>
 *   <li>语义明确(每个调用点知道自己发的是哪种)
 *   <li>未来若 register / heartbeat 字段集分叉(decoupling),SDK 可独立演进
 * </ul>
 *
 * <p>Phase 0 ({@code docs/plans/sdk-roadmap-2026-h2.md} §2)— SDK 协议演进基础。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartbeatRequest(
    String tenantId,
    String workerCode,
    String workerGroup,
    String status,
    String hostName,
    String hostIp,
    String processId,
    // Python SDK PR #320 对齐:heartbeat 也带 buildId(register 时已发),消除 worker_registry
    // 行被运维误删后兜底降级 register 路径丢字段的窗口。null 由 NON_NULL 序列化策略略过。
    String buildId,
    Instant heartbeatAt,
    List<String> capabilityTags,
    Integer currentLoad,
    // 2026-06-03 docs/design/pipeline-stage-progress-display.md:流式 stage(IMPORT LOAD /
    // EXPORT GENERATE)行级进度上报。仅 LOAD/GENERATE 两个 stage 在跑时非空,其余 stage / 空闲态 null。
    Long rowsProcessed,
    // 总量已知时携带;未知时(EXPORT 流式源 / 无法预估)null,FE 降级仅显示计数器不显 ETA。
    Long totalRowsHint) {}
