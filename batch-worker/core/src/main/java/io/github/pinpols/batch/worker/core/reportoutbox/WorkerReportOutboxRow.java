package io.github.pinpols.batch.worker.core.reportoutbox;

/** 一次领取持有的载荷与凭据，后续状态变更必须同时核对该凭据。 */
public record WorkerReportOutboxRow(long id, String payloadJson, String claimToken) {}
