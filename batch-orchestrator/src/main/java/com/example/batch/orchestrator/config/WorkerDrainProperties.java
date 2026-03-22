package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.drain")
public class WorkerDrainProperties {

    /**
     * Default drain window before orchestrator takes over in-flight tasks.
     */
    private int defaultTimeoutSeconds = 600;

    /**
     * How often to poll workers in DRAINING for deadline expiry.
     */
    private long checkIntervalMillis = 15000L;

    private boolean enabled = true;
}
