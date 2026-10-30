package io.github.pinpols.batch.orchestrator.domain.query;

/** 全局等待分片扫描水位及并发版本。 */
public record WaitingPartitionScanCursor(
    long lastPartitionId, long cycleUpperId, boolean dryRunPhase, long version) {}
