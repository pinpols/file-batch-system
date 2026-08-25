package io.github.pinpols.batch.orchestrator.domain.entity;

/**
 * job_partition 按实例聚合后的状态摘要。
 *
 * <p>普通非 DAG 实例只需要这些计数推进 job_instance，不需要把每个分区的状态行加载到 JVM。
 * DAG 实例仍使用 {@link PartitionStatusRef}，因为节点推进还需要分区 ID 与节点映射。
 */
public record PartitionStatusSummary(
    long totalCount, long successCount, long failedCount, long broadFailedCount) {}
