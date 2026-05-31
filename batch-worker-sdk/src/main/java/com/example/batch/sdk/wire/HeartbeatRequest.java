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
    Instant heartbeatAt,
    List<String> capabilityTags,
    Integer currentLoad) {}
