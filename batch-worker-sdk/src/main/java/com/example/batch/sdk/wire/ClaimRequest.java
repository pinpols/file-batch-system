package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SDK wire DTO — {@code POST /internal/tasks/{taskId}/claim} 请求体。
 *
 * <p>对齐平台 {@code com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest}。
 *
 * <ul>
 *   <li>{@code tenantId} —— 必填,多租隔离
 *   <li>{@code workerId} —— SDK 端 {@code workerCode}(ADR-035 §9:workerId==workerCode,P4 后 server
 *       分配)
 *   <li>{@code partitionInvocationId} —— ADR-014:可选;mismatched invocation 平台 reject
 * </ul>
 *
 * <p>Phase 0 ({@code docs/plans/sdk-roadmap-2026-h2.md} §2)— SDK 协议演进基础。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimRequest(String tenantId, String workerId, String partitionInvocationId) {}
