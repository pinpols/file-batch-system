package io.github.pinpols.batch.worker.dispatchs.domain;

/** 用于判断恢复时是否可安全调用远端通道的持久分发状态。 */
public record DispatchRecordSnapshot(
    String dispatchStatus,
    String receiptStatus,
    String externalRequestId,
    String receiptCode,
    String errorCode,
    String errorMessage) {}
