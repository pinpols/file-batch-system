package io.github.pinpols.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.trigger.runtime")
/** Trigger 轮询、补偿与运行时保护参数。 */
public class TriggerRuntimeProperties {

  private long misfireCatchUpThresholdSeconds = 60L;

  private long readinessWindowSeconds = 7200L;

  private long readinessRecheckIntervalSeconds = 30L;
}
