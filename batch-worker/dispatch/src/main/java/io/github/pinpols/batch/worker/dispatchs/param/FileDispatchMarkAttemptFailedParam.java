package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 当前外发意图失败参数；保留期望状态 CAS。 */
@Builder
public record FileDispatchMarkAttemptFailedParam(
    String tenantId,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String receiptStatus,
    String expectedDispatchStatus,
    String errorCode,
    String errorMessage) {}
