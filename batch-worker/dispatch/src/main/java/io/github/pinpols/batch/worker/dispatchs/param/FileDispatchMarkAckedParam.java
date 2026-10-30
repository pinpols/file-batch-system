package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 回执确认参数；只有 SENT 前态允许推进。 */
@Builder
public record FileDispatchMarkAckedParam(
    String tenantId,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String receiptStatus,
    String receiptCode,
    String expectedDispatchStatus) {}
