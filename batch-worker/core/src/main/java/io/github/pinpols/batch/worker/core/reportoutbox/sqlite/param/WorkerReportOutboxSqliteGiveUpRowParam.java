package io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param;

import lombok.Builder;

/** 标记当前SQLite载荷不可投递，不能覆盖新载荷或其他领取者。 */
@Builder
public record WorkerReportOutboxSqliteGiveUpRowParam(
    long id,
    String publishStatus,
    long now,
    int maxAttempts,
    String statusPublishing,
    String claimToken) {}
