package com.example.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分发回执轮询配置属性。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.receipt-poll")
public class DispatchReceiptPollProperties {

  private boolean enabled = true;
  private long intervalMillis = 60_000L;
  private int batchSize = 50;
}
