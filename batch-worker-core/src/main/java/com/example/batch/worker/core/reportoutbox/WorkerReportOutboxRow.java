package com.example.batch.worker.core.reportoutbox;

public record WorkerReportOutboxRow(long id, String payloadJson) {}
