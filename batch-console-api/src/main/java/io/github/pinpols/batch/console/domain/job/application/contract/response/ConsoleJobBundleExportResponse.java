package io.github.pinpols.batch.console.domain.job.application.contract.response;

import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;

/** Job Bundle 导出结果：租户 + jobCode + 数量汇总 + 完整配置束。 */
public record ConsoleJobBundleExportResponse(
    String tenantId,
    String jobCode,
    ConsoleJobBundleSummaryResponse summary,
    ConfigSyncBundlePayload bundle) {}
