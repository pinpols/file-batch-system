package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 已知失败重新外发的 CAS 参数；不改变未知结果拦截条件。 */
@Builder
public record FileDispatchBeginAttemptParam(
    String tenantId,
    Long fileId,
    String channelCode,
    String unknownOutcomeCode,
    String dispatchStatus,
    String receiptStatus) {}
