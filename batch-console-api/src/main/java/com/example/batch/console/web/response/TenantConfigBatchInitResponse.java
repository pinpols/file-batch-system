package com.example.batch.console.web.response;

import java.util.ArrayList;
import java.util.List;

/**
 * 租户配置批量初始化响应。
 */
public record TenantConfigBatchInitResponse(
        String batchOperationId,
        int totalTenants,
        int successTenants,
        int failureTenants,
        boolean dryRun,
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
            int failed,
            List<ItemDetail> details
    ) {
        public static ItemStats empty() {
            return new ItemStats(0, 0, 0, 0, List.of());
        }
    }

    public record ItemDetail(
            String code,
            String action,
            String errorMessage
    ) {
        /** action 常量。 */
        public static final String CREATED = "CREATED";
        public static final String UPDATED = "UPDATED";
        public static final String SKIPPED = "SKIPPED";
        public static final String FAILED = "FAILED";
    }

    /**
     * 可变的 ItemStats 构建器，收集明细后一次性转换为不可变 record。
     */
    public static final class ItemStatsAccumulator {
        private int created;
        private int updated;
        private int skipped;
        private int failed;
        private final List<ItemDetail> details = new ArrayList<>();

        public void recordCreated(String code) {
            created++;
            details.add(new ItemDetail(code, ItemDetail.CREATED, null));
        }

        public void recordUpdated(String code) {
            updated++;
            details.add(new ItemDetail(code, ItemDetail.UPDATED, null));
        }

        public void recordSkipped(String code) {
            skipped++;
            details.add(new ItemDetail(code, ItemDetail.SKIPPED, null));
        }

        public void recordFailed(String code, String errorMessage) {
            failed++;
            details.add(new ItemDetail(code, ItemDetail.FAILED, errorMessage));
        }

        public ItemStats toItemStats() {
            return new ItemStats(created, updated, skipped, failed, List.copyOf(details));
        }
    }
}
