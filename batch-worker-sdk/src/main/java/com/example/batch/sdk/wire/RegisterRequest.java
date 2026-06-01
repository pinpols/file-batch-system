package com.example.batch.sdk.wire;

import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * SDK wire DTO — {@code POST /internal/workers/register} 请求体。
 *
 * <p>对齐平台 {@code com.example.batch.common.dto.WorkerHeartbeatDto}。字段集是协议契约,任何字段重命名 / 删除 / 新增必须同时:
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
    Instant heartbeatAt,
    List<String> capabilityTags,
    Integer currentLoad,
    // SDK Phase 3 M3.1 — 自定义 taskType 描述符;heartbeat 不带,仅 register 上报。
    List<SdkTaskTypeDescriptor> taskTypes) {}
