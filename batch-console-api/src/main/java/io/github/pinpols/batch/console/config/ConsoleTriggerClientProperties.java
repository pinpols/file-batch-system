package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.console.trigger")
public class ConsoleTriggerClientProperties {

  private String baseUrl = "";

  private long connectTimeoutMillis = 5000L;

  private long readTimeoutMillis = 30000L;
}
