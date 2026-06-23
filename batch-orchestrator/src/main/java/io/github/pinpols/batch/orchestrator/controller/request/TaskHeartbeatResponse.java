package io.github.pinpols.batch.orchestrator.controller.request;

/**
 * ORCH-P4-1：{@code POST /internal/tasks/{taskId}/renew} 响应体。
 *
 * <p>{@code cancelRequested=true} 表示平台已请求取消本 task(运维 cancel 端点 / ORCH-P4-2 超时)。SDK 收到后主动停长循环,不等
 * lease 超时。旧版 SDK 不解析此 body(HTTP 200 即续租成功),协议向前兼容。
 */
public record TaskHeartbeatResponse(boolean cancelRequested) {}
