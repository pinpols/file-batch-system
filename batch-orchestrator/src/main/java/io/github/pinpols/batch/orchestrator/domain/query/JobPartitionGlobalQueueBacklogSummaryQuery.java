package io.github.pinpols.batch.orchestrator.domain.query;

import lombok.Builder;

/** 绑定一次summarizeGlobalQueueBacklog操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record JobPartitionGlobalQueueBacklogSummaryQuery(
    String createdStatus,
    String waitingStatus,
    String readyStatus,
    String runningStatus,
    String retryingStatus) {}
