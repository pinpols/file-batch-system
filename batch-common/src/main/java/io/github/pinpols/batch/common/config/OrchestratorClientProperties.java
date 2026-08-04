package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared configuration for clients that call the orchestrator service. */
@Data
@ConfigurationProperties(prefix = "batch.orchestrator")
public class OrchestratorClientProperties {

  private String baseUrl;
}
