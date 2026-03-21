package com.example.batch.worker.imports.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.import.scanner")
public class MinioImportScannerProperties {

    private boolean enabled = true;
    private long pollIntervalMillis = 30000L;
    private int batchSize = 200;
    private String prefix = "ingress/";
    private boolean requireDoneFile = false;
    private long stabilityWindowSeconds = 30L;
    private String sourceType = "SYSTEM";
    private String defaultBizType = "IMPORT_SCAN";
}
