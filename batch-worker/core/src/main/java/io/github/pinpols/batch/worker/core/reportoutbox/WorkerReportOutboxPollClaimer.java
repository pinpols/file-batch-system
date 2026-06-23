package io.github.pinpols.batch.worker.core.reportoutbox;

import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/** 抢占一行 outbox（NEW→PUBLISHING）：PostgreSQL 用 {@code SKIP LOCKED}，须在短事务内完成。 */
public class WorkerReportOutboxPollClaimer {

  private final WorkerReportOutboxRepository repository;
  private final TransactionTemplate transactionTemplate;

  public WorkerReportOutboxPollClaimer(
      WorkerReportOutboxRepository repository, TransactionTemplate transactionTemplate) {
    this.repository = repository;
    this.transactionTemplate = transactionTemplate;
  }

  public Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis) {
    return transactionTemplate.execute(status -> repository.claimNext(nowEpochMillis));
  }
}
