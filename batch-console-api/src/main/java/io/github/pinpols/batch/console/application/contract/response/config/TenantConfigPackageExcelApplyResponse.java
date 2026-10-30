package io.github.pinpols.batch.console.application.contract.response.config;

import lombok.Builder;

/** 租户配置包 Excel 应用响应：各类型新增与更新数量。 */
@Builder
public record TenantConfigPackageExcelApplyResponse(
    String uploadToken,
    String tenantId,
    int resourceQueueInserted,
    int resourceQueueUpdated,
    int businessCalendarInserted,
    int businessCalendarUpdated,
    int batchWindowInserted,
    int batchWindowUpdated,
    int jobInserted,
    int jobUpdated,
    int channelInserted,
    int channelUpdated,
    int fileTemplateInserted,
    int fileTemplateUpdated,
    int workflowInserted,
    int workflowUpdated) {}
