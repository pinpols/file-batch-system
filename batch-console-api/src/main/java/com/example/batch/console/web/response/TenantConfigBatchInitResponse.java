package com.example.batch.console.web.response;

import java.util.List;

/**
 * 租户配置批量初始化响应。
 */
public record TenantConfigBatchInitResponse(
        int totalTenants,
        int successTenants,
        int failureTenants,
        List<TenantInitResult> results
) {

    public record TenantInitResult(
            String tenantId,
            boolean success,
            String errorMessage,
            ItemStats jobDefinitions,
            ItemStats workflowDefinitions,
            ItemStats pipelineDefinitions,
            ItemStats fileChannels,
            ItemStats fileTemplates
    ) {}

    public record ItemStats(
            int created,
            int updated,
            int skipped,
            int failed
    ) {
        public static ItemStats empty() {
            return new ItemStats(0, 0, 0, 0);
        }
    }
}
