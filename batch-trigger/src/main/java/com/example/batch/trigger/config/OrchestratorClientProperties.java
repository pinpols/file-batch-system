package com.example.batch.trigger.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.orchestrator")
@Data
public class OrchestratorClientProperties {

    private String baseUrl;
}
