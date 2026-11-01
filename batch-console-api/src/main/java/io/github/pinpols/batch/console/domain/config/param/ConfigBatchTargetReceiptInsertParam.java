package io.github.pinpols.batch.console.domain.config.param;

/** 目标事务的已确认结果及其原始位置。 */
public record ConfigBatchTargetReceiptInsertParam(
    String auditTenantId,
    String operationId,
    int targetOrdinal,
    String tenantId,
    String resultJson) {}
