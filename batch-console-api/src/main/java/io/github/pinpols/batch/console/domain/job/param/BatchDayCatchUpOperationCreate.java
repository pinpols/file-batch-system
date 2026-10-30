package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;

/** 首次冻结批量日补跑请求；状态和版本由持久化入口初始化。 */
@Builder
public record BatchDayCatchUpOperationCreate(
    String tenantId,
    String requestKeyHash,
    String requestHash,
    String requestJson,
    String snapshotJson,
    String itemsJson) {}
