package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 绑定一次giveUpRow操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record WorkerReportOutboxPgGiveUpRowParam(
    long id,
    String publishStatus,
    long now,
    int maxAttempts,
    String statusPublishing) {}
