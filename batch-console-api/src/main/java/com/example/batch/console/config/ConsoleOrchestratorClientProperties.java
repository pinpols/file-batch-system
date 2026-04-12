package com.example.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.console.orchestrator")
public class ConsoleOrchestratorClientProperties {

  private String baseUrl = "http://127.0.0.1:8082";
}
