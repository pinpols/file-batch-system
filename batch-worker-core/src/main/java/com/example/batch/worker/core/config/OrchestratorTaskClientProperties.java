package com.example.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.task-client")
public class OrchestratorTaskClientProperties {

    private String baseUrl;
    private int batchSize = 10;
}
