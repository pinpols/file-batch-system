package com.example.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.lease")
public class WorkerLeaseProperties {

  private long renewIntervalMillis = 10000L;
}
