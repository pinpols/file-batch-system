package io.github.pinpols.batch.console.domain.job.view;

import lombok.Builder;

/** 批量日补跑的原请求、首次冻结结果与当前对账状态。 */
@Builder
public record BatchDayCatchUpOperation(
    String tenantId,
    String requestKeyHash,
    String requestHash,
    String requestJson,
    String snapshotJson,
    String itemsJson,
    String operationStatus,
    long revision) {}
