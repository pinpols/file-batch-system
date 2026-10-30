package io.github.pinpols.batch.worker.dispatchs.param;

import lombok.Builder;

/** 失败记录写入参数；字段可空性沿用原数据库契约。 */
@Builder
public record FileDispatchMarkFailedParam(
    String tenantId,
    Long fileId,
    String channelCode,
    String dispatchStatus,
    String receiptStatus,
    String errorCode,
    String errorMessage) {}
