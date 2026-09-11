package io.github.pinpols.batch.orchestrator.config;

import java.time.Duration;
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

  /** ShedLock 最短持锁时间；生产默认 15 秒，容量环境可独立缩短。 */
  private Duration lockAtLeastFor = Duration.ofSeconds(15);

  /** ShedLock 故障兜底时长；实例非正常退出后，其他实例最迟在该时长后接管。 */
  private Duration lockAtMostFor = Duration.ofMinutes(1);

  /** 未绑定实例的 RUNNING entry 超过该时长后允许回收，避免启动窗口崩溃造成永久卡住。 */
  private long claimTimeoutMillis = 300_000L;
}
