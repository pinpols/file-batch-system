package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * SDK wire DTO — {@code POST /internal/tasks/{taskId}/report} 请求体。
 *
 * <p>对齐平台 {@code com.example.batch.orchestrator.controller.request.TaskExecutionReportDto}。
 *
 * <p>注意字段命名陷阱(历史错名 → 平台读不到):
 *
 * <ul>
 *   <li>{@code outputs}(复数)— 不是 {@code output}(ADR-009 Stage 1.2 节点产出 Map)
 *   <li>{@code errorCode} / {@code resultSummary}(已废 {@code errorClass} / {@code errorMessage})
 * </ul>
 *
 * <p>Phase 0 ({@code docs/plans/sdk-roadmap-2026-h2.md} §2)— SDK 协议演进基础。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportRequest(
    Long taskId,
    String tenantId,
    String workerId,
    String traceId,
    boolean success,
    String code,
    String message,
    String resultSummary,
    String errorCode,
    String highWaterMarkOut,
    Map<String, Object> outputs,
    String partitionInvocationId,
    String failureClass,
    List<Map<String, Object>> verifierFailures) {}
