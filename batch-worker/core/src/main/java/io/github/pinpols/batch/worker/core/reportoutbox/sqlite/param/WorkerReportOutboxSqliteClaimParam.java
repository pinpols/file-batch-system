package io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param;

import lombok.Builder;

/** 一次SQLite领取，只允许将仍为NEW的行绑定到本次凭据。 */
@Builder
public record WorkerReportOutboxSqliteClaimParam(
    long id, String statusPublishing, long now, String statusNew, String claimToken) {}
