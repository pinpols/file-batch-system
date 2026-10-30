package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 补偿写入参数；保留已成功回执的原判定。 */
@Builder
public record FileDispatchMarkCompensatedParam(
    String tenantId,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String receiptStatusSuccess,
    String receiptStatusFailed,
    String errorCode,
    String errorMessage) {}
