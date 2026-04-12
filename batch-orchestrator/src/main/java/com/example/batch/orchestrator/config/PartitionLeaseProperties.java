package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.partition-lease")
public class PartitionLeaseProperties {

  private long expireSeconds = 60L;
  private long reclaimIntervalMillis = 15000L;
}
