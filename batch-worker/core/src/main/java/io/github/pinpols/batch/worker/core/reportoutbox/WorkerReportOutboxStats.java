package io.github.pinpols.batch.worker.core.reportoutbox;

public record WorkerReportOutboxStats(
    long newCount, long publishingCount, long giveUpCount, long stalePublishingCount) {}
