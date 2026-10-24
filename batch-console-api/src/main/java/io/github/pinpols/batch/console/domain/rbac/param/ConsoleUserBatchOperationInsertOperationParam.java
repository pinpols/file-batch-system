package io.github.pinpols.batch.console.domain.rbac.param;

import lombok.Builder;

import java.util.UUID;
/** 绑定一次insertOperation操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleUserBatchOperationInsertOperationParam(
    UUID operationId,
    UUID requestId,
    String actor,
    String sourceDigest,
    int accountCount,
    String tenantIds) {}
