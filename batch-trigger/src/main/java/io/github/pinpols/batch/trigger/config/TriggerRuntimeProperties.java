package io.github.pinpols.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.trigger.runtime")
public class TriggerRuntimeProperties {

  private long misfireCatchUpThresholdSeconds = 60L;
}
