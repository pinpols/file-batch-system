package io.github.pinpols.batch.console.domain.audit.web.response;

import java.time.Instant;

/**
 * 通用控制台用户操作审计 — 查询响应 DTO,1:1 对齐 {@code batch.console_operation_audit} 表 + {@link
 * io.github.pinpols.batch.console.domain.audit.support.OperationAuditEvent} 字段。
 *
 * <p>API 暴露给 FE / 运维查「谁哪个时间做了什么」。
 */
public record ConsoleOperationAuditResponse(
    Long id,
    String tenantId,
    String aggregateType,
    String aggregateId,
    String action,
    String operatorId,
    String operatorRole,
    String result,
    String errorCode,
    String errorMessage,
    /** params 是 jsonb,这里以原 JSON 字符串透传给 FE,FE 自己解析展示 */
    String params,
    String traceId,
    String requestId,
    String ipHash,
    String uaHash,
    Integer eventVersion,
    Instant createdAt) {}
