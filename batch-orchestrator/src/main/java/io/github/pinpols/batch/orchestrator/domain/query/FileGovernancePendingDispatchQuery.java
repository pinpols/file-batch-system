package io.github.pinpols.batch.orchestrator.domain.query;

import lombok.Builder;

/** 待回执分发计数参数；保留分发状态与回执状态的独立筛选。 */
@Builder
public record FileGovernancePendingDispatchQuery(
    String tenantId,
    Long fileId,
    String dispatchCreatedStatus,
    String dispatchSentStatus,
    String receiptPendingStatus) {}
