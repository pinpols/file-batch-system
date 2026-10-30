package io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param;

import lombok.Builder;

/** 按当前领取凭据安排下一次SQLite回报重试。 */
@Builder
public record WorkerReportOutboxSqliteUpdateRetryParam(
    long id,
    int attemptCount,
    long nextAttemptAt,
    long updatedAt,
    String publishStatus,
    String statusPublishing,
    String claimToken) {}
