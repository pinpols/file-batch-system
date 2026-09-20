package io.github.pinpols.batch.console.domain.job.application.contract.response;

import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;

/** Job Bundle 落库结果（create / import 共用）：租户 + 数量汇总 + 批量初始化结果。 */
public record ConsoleJobBundleResultResponse(
    String tenantId,
    ConsoleJobBundleSummaryResponse summary,
    TenantConfigBatchInitResponse result) {}
