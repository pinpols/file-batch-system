package io.github.pinpols.batch.console.web.response.config;

/** 配置同步导入结果。 */
public record ConfigSyncImportResponse(
    Long syncLogId, ConfigSyncSummaryResponse summary, TenantConfigBatchInitResponse result) {}
