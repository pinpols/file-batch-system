package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-020 §影响面 §性能 — batch_day_replay dispatcher 配置。 */
@Data
@ConfigurationProperties(prefix = "batch.replay.dispatch")
public class BatchDayReplayDispatchProperties {

  /** 启用开关；false 时 scheduler 直接 skip。 */
  private boolean enabled = true;

  /** 扫描周期（毫秒），默认 30 秒。 */
  private long pollIntervalMillis = 30_000L;

  /** 单轮处理 RUNNING session 的上限。 */
  private int sessionBatchSize = 20;

  /** 单 session 单轮处理 PENDING entries 的上限（rate-limit）。 */
  private int entryBatchSize = 50;

  /** 未绑定实例的 RUNNING entry 超过该时长后允许回收，避免启动窗口崩溃造成永久卡住。 */
  private long claimTimeoutMillis = 300_000L;
}
