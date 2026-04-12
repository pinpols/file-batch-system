package com.example.batch.console.web.response;

/** 租户配置包 Excel 应用响应：各类型 inserted / updated 数量。 */
public record TenantConfigPackageExcelApplyResponse(
        String uploadToken,
        String tenantId,
        int jobInserted,
        int jobUpdated,
        int channelInserted,
        int channelUpdated,
        int routingInserted,
        int routingUpdated,
        int pipelineInserted,
        int pipelineUpdated,
        int workflowInserted,
        int workflowUpdated) {}
