package io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param;

import lombok.Builder;

/** 放弃当前SQLite投递尝试，保留领取状态与凭据归属条件。 */
@Builder
public record WorkerReportOutboxSqliteGiveUpParam(
    long id,
    String publishStatus,
    int attemptCount,
    long updatedAt,
    String statusPublishing,
    String claimToken) {}
