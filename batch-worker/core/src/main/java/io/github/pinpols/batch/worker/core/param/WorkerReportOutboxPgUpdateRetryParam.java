package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 按当前领取凭据安排下一次PG回报重试。 */
@Builder
public record WorkerReportOutboxPgUpdateRetryParam(
    long id,
    int attemptCount,
    long nextAttemptAt,
    long updatedAt,
    String publishStatus,
    String statusPublishing,
    String claimToken) {}
