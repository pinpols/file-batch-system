package io.github.pinpols.batch.orchestrator.application.trigger;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 启动恢复锚点的扫描参数；重试上限与退避用于避免无限重投。 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.launch-recovery")
public class TriggerLaunchRecoveryProperties {

  /** 周期扫描间隔（毫秒）。 */
  private long pollIntervalMillis = 30_000L;

  /** 单轮最多处理的恢复记录数。 */
  private int batchSize = 50;

  /** 最大重投次数；达到上限置 DEAD 并保留最后错误码。 */
  private int maxAttempts = 5;

  /** 首次退避秒数，按尝试次数指数增长。 */
  private long baseBackoffSeconds = 30L;

  /** 退避上限秒数。 */
  private long maxBackoffSeconds = 900L;
}
