package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 仅将当前PG领取载荷标记为人工处理。 */
@Builder
public record WorkerReportOutboxPgGiveUpRowParam(
    long id,
    String publishStatus,
    long now,
    int maxAttempts,
    String statusPublishing,
    String claimToken) {}
