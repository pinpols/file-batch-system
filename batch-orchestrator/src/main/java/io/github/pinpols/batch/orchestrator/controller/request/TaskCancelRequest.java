package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ORCH-P4-1：{@code POST /internal/tasks/{taskId}/cancel} 请求体。运维 / 平台侧请求取消一个 RUNNING task。
 *
 * <p>{@code reason} 仅作审计 / 日志用,可空。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskCancelRequest(String tenantId, String reason) {}
