package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 触发请求启动连续性指标配置。 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.launch.metrics")
public class TriggerLaunchContinuityMetricsProperties {

  private long staleAfterSeconds = 300L;
}
