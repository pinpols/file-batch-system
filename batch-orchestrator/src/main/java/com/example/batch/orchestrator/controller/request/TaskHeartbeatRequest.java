package com.example.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ORCH-P4-1：{@code POST /internal/tasks/{taskId}/renew} 请求体。
 *
 * <p>在旧 claim/renew 三字段({@code tenantId/workerId/partitionInvocationId})基础上新增可选 {@code details} ——
 * worker 上报的进度 / checkpoint 快照(任意 JSON 对象)。旧版 SDK 不带 {@code details}(null)仍 100% 兼容:不写进度,仅续租。
 *
 * <p><b>敏感凭据禁入 {@code details}</b>(DB 密码 / OAuth secret 走环境变量,roadmap §5.5)—— details 会落 job_task 供
 * console 任务详情读取。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskHeartbeatRequest(
    String tenantId, String workerId, String partitionInvocationId, Object details) {}
