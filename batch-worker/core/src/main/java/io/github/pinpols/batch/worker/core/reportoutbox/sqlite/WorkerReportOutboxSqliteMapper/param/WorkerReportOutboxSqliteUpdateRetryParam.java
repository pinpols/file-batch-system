package io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper.param;

import lombok.Builder;

/** 绑定一次updateRetry操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record WorkerReportOutboxSqliteUpdateRetryParam(
    long id,
    int attemptCount,
    long nextAttemptAt,
    long updatedAt,
    String publishStatus) {}
