package com.example.batch.worker.core.reportoutbox;

import com.example.batch.common.utils.Texts;
import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.report-outbox")
public class WorkerReportOutboxProperties {

  private boolean enabled = false;

  /**
   * {@link WorkerReportOutboxStorage#PLATFORM_PG}：依赖已迁移的 {@code batch.worker_report_outbox}； {@link
   * WorkerReportOutboxStorage#SQLITE}：使用 {@link #sqlitePath} 本地文件。
   */
  private WorkerReportOutboxStorage storage = WorkerReportOutboxStorage.PLATFORM_PG;

  /** 空则回退 user.home/.batch-platform/worker-report-outbox.db（仅 SQLITE）。 */
  private String sqlitePath = "";

  private long pollIntervalMillis = 5_000L;

  /** Poller 维度：单行最多投递尝试次数（每次尝试仍会走 HTTP 内置重试链）。 */
  private int maxPublishAttempts = 48;

  private long initialBackoffMillis = 1_000L;
  private long maxBackoffMillis = 300_000L;
  private long jitterMillis = 500L;
  private int pollBatchSize = 8;

  /**
   * 当 {@link com.example.batch.worker.core.infrastructure.WorkerTaskLeaseRenewer} 续租熔断 OPEN 时，跳过
   * report outbox 轮询，避免 orch 不可达时无意义 hammer。
   */
  private boolean pausePollWhenRenewCircuitOpen = true;

  /** PUBLISHING 超过该时间未删除则恢复为 NEW（进程崩溃兜底），毫秒。 */
  private long publishingStaleRecoverAfterMillis = 120_000L;

  /** 陈旧 PUBLISHING 扫描间隔。 */
  private long stalePublishingRecoverIntervalMillis = 60_000L;

  public Path resolveSqlitePath() {
    if (Texts.hasText(sqlitePath)) {
      return Path.of(sqlitePath);
    }
    return Path.of(System.getProperty("user.home"), ".batch-platform", "worker-report-outbox.db");
  }
}
