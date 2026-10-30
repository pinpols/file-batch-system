package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ORCH-P4-1：{@code POST /internal/tasks/{taskId}/renew} 请求体。
 *
 * <p>{@code tenantId/workerId/partitionInvocationId} 标识当前租约所有者；可选的 {@code details}
 * 保存 Worker 的进度和检查点快照。未提交详情时保留普通续租行为，不推算缺失的实际执行起点。
 *
 * <p>{@code details.executionStarted=true} 用于执行线程进入实际适配器前确认首次执行入口；
 * 平台先验证当前租约、worker 和 invocation，再独立记录 Job 的 execution_started_at，不设置非幂等副作用标记。
 *
 * <p>详情会持久化至 job_task 并供控制台读取，因此禁止包含数据库密码、OAuth 密钥等敏感凭据。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskHeartbeatRequest(
    String tenantId, String workerId, String partitionInvocationId, Object details) {}
