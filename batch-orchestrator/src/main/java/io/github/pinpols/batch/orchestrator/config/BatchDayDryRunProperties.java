package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 整批量日 dry-run 的显式开关与容量边界。 */
@Data
@ConfigurationProperties(prefix = "batch.replay.dry-run")
public class BatchDayDryRunProperties {

  /** 默认关闭；开启前必须完成目标环境副作用隔离验收。 */
  private boolean enabled;

  /** 单会话最多物化的演练 entry 数。 */
  private int maxActiveEntries = 200;

  /** dry-run 结果默认保留天数。 */
  private int retentionDays = 7;
}
