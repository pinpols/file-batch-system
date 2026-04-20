package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.resource-scheduler")
public class ResourceSchedulerProperties {

  private int waitingDispatchBatchSize = 100;
  private long waitingDispatchIntervalMillis = 10000L;
  private int quotaResetSlidingWindowHours = 24;
  private long quotaResetScanIntervalMillis = 60000L;
  private boolean quotaResetEnabled = true;

  /** 全局并发上限（所有租户合计的运行中任务数）。 值 <= 0 表示关闭。 */
  private long globalMaxRunningJobs = 0;

  /**
   * 共享 worker 池的 fallback 租户。当某租户的目标 workerGroup 下没有 ONLINE worker 时，
   * selector 会退到此租户再查一次——仅用于本地联调 / 共享 dev 环境，生产不设置。
   * 空值（默认）禁用 fallback，严格保留 CLAUDE.md §多租户隔离 的原语义。
   */
  private String sharedTenantFallback = "";
}
