package io.github.pinpols.batch.console.web.response.config;

import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;

/** 配置同步导出结果。 */
public record ConfigSyncExportResponse(
    String sourceTenantId,
    String sourceEnv,
    String targetEnv,
    ConfigSyncSummaryResponse summary,
    ConfigSyncBundlePayload bundle) {}
