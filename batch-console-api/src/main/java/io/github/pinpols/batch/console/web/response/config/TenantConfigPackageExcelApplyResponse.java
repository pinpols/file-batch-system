package io.github.pinpols.batch.console.web.response.config;

/** 租户配置包 Excel 应用响应：各类型 inserted / updated 数量。 */
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
    int pipelineInserted,
    int pipelineUpdated,
    int workflowInserted,
    int workflowUpdated) {}
