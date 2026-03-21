package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.file-governance")
public class FileGovernanceProperties {

    private final Latency latency = new Latency();
    private final Archive archive = new Archive();
    private final Reconcile reconcile = new Reconcile();

    @Data
    public static class Latency {
        private boolean enabled = true;
        private long pollIntervalMillis = 30000L;
        private long arrivalDelayThresholdSeconds = 600L;
        private long processingDelayThresholdSeconds = 900L;
        private int sampleSize = 20;
    }

    @Data
    public static class Archive {
        private boolean enabled = true;
        private long cleanupIntervalMillis = 60000L;
        private int cleanupBatchSize = 100;
        private int retentionDays = 7;
    }

    @Data
    public static class Reconcile {
        private boolean enabled = true;
        private long pollIntervalMillis = 60000L;
        private int batchSize = 200;
        private String defaultTenantId = "default-tenant";
        private String prefix = "";
        private boolean includeTemporaryObjects = false;
    }
}
