package io.github.pinpols.batch.worker.core.reportoutbox;

/** 持久化方言（与 {@link WorkerReportOutboxStorage} 对应）。 */
public enum WorkerReportOutboxDialect {
  SQLITE,
  POSTGRESQL
}
