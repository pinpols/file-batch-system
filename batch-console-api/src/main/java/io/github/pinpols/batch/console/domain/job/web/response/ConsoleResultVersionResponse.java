package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.Instant;

/**
 * ADR-017 结果版本响应（console 透传 orchestrator {@code ResultVersionEntity} 的 JSON 投影）。 字段与下游 record
 * 一一对应，console 不引入 orchestrator 模块，故本地镜像其序列化形态。
 */
public record ConsoleResultVersionResponse(
    Long id,
    String tenantId,
    String businessKey,
    Integer versionNo,
    Long jobInstanceId,
    String status,
    Instant effectiveAt,
    Instant deactivatedAt,
    String payloadStorage,
    String payloadJson,
    String payloadRef,
    Instant generatedAt,
    String generatedBy,
    String promotionPolicy,
    Long approvalId,
    Instant createdAt,
    Instant updatedAt,
    String dqGateStatus) {}
