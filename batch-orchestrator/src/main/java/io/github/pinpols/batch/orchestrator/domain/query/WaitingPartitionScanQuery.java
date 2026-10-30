package io.github.pinpols.batch.orchestrator.domain.query;

/** 当前轮次中按执行模式和 ID 范围扫描等待分片。 */
public record WaitingPartitionScanQuery(
    int batchSize, String waitingStatus, long afterId, long upperId, boolean dryRun) {}
