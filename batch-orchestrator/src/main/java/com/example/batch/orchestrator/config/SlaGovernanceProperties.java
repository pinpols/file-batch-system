package com.example.batch.orchestrator.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.sla")
public class SlaGovernanceProperties {

    private boolean enabled = true;
    private long pollIntervalMillis = 30000L;
    private int batchSize = 200;
}
