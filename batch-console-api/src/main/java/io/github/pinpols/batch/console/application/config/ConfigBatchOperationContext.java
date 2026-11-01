package io.github.pinpols.batch.console.application.config;

/** 已登记的批量操作身份，用于将目标结果关联到原请求。 */
public record ConfigBatchOperationContext(String auditTenantId, String operationId) {}
