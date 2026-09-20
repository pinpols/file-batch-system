package io.github.pinpols.batch.console.application.contract.response.config;

/** 配置同步预览结果。 */
public record ConfigSyncPreviewResponse(
    String tenantId,
    String sourceTenantId,
    String sourceEnv,
    String targetEnv,
    ConfigSyncSummaryResponse summary) {}
