package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 仅将当前PG领取尝试标记为已放弃。 */
@Builder
public record WorkerReportOutboxPgUpdateGiveUpParam(
    long id,
    String publishStatus,
    int attemptCount,
    long updatedAt,
    String statusPublishing,
    String claimToken) {}
