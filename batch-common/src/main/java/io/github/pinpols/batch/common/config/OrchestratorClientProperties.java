package io.github.pinpols.batch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 供调用 Orchestrator 服务的客户端共享配置。 */
@Data
@ConfigurationProperties(prefix = "batch.orchestrator")
public class OrchestratorClientProperties {

    private String baseUrl;
}
