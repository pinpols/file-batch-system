package io.github.pinpols.batch.worker.core.reportoutbox;

/**
 * 存储后端：{@link #PLATFORM_PG} 使用主平台库（Flyway {@code batch.worker_report_outbox}）； {@link #SQLITE}
 * 使用本地文件。
 */
public enum WorkerReportOutboxStorage {
  SQLITE,
  PLATFORM_PG;

  public WorkerReportOutboxDialect dialect() {
    return this == SQLITE ? WorkerReportOutboxDialect.SQLITE : WorkerReportOutboxDialect.POSTGRESQL;
  }
}
