package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 批次日缺口扫描与依赖等待配置。 */
@Data
@ConfigurationProperties(prefix = "batch.batch-day")
public class BatchDayScanProperties {

  private long dependencyWaitTimeoutSeconds = 86_400L;
  private int catchupDaysPerScan = 14;
}
