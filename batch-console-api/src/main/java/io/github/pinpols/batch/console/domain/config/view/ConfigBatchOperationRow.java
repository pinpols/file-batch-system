package io.github.pinpols.batch.console.domain.config.view;

/** 原批量操作登记，用于按顺序核对各目标的已确认结果。 */
public record ConfigBatchOperationRow(
    String auditTenantId,
    String operationId,
    String requestIdentityHash,
    String operationKind,
    String targetsJson,
    String responseMetadataJson) {}
