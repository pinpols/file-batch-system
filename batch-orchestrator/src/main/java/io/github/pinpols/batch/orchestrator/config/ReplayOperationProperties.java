package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 限制重放操作同步预览与准入的工作量。 */
@Data
@ConfigurationProperties(prefix = "batch.replay-operation")
public class ReplayOperationProperties {

  /** 单个操作可评估的最大日历跨度，包含起止日期。 */
  private int maxRangeDays = 31;
}
