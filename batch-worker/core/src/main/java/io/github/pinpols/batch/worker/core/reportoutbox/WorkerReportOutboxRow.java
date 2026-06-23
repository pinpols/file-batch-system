package io.github.pinpols.batch.worker.core.reportoutbox;

public record WorkerReportOutboxRow(long id, String payloadJson) {}
