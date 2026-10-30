package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 供调用 Orchestrator 服务的客户端共享配置。 */
@Data
@ConfigurationProperties(prefix = "batch.orchestrator")
public class OrchestratorClientProperties {

  private String baseUrl;
}
