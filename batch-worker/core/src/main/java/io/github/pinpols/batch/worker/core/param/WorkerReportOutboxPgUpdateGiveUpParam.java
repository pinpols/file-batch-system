package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;

/** 绑定一次updateGiveUp操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record WorkerReportOutboxPgUpdateGiveUpParam(
    long id,
    String publishStatus,
    int attemptCount,
    long updatedAt,
    String statusPublishing) {}
