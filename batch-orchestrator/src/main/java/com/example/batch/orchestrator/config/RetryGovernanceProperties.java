package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.retry")
public class RetryGovernanceProperties {

  private int batchSize = 100;
  private long pollIntervalMillis = 10000L;
  private long fixedDelaySeconds = 60L;
  private long exponentialMultiplier = 2L;
  private long maxDelaySeconds = 3600L;
  private int defaultMaxRetryCount = 3;
}
