package io.github.pinpols.batch.worker.dispatchs.domain;

/** A persisted dispatch state used to decide whether recovery may safely call the remote channel. */
public record DispatchRecordSnapshot(
    String dispatchStatus,
    String receiptStatus,
    String externalRequestId,
    String receiptCode,
    String errorCode,
    String errorMessage) {}
