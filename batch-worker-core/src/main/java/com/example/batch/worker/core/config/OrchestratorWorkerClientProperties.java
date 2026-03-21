package com.example.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.orchestrator")
public class OrchestratorWorkerClientProperties {

    private String baseUrl;
}
