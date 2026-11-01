package io.github.pinpols.batch.console.domain.config.param;

/** 原批量操作登记所需的非敏感信息。 */
public record ConfigBatchOperationInsertParam(
    String auditTenantId,
    String operationId,
    String requestIdentityHash,
    String operationKind,
    String targetsJson,
    String responseMetadataJson) {}
