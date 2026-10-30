package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 外发成功写入参数；回执码允许为空并沿用原 SQL 的保留规则。 */
@Builder
public record FileDispatchMarkSentParam(
    String tenantId,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String externalRequestId,
    String receiptCode,
    String receiptStatus,
    String expectedDispatchStatus) {}
