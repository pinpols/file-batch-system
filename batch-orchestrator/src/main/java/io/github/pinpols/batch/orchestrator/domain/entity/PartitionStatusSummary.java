package io.github.pinpols.batch.orchestrator.domain.entity;

/**
 * job_partition 按实例聚合后的状态摘要。
 *
 * <p>普通非 DAG 实例只需要这些计数推进 job_instance，不需要把每个分区的状态行加载到 JVM。
 * DAG 实例仍使用 {@link PartitionStatusRef}，因为节点推进还需要分区 ID 与节点映射。
 */
public record PartitionStatusSummary(
    long totalCount, long successCount, long failedCount, long broadFailedCount) {

  /**
   * MyBatis 将 PostgreSQL count(*) 映射为包装类型；保留显式重载避免运行时反射找不到构造器。
   */
  public PartitionStatusSummary(
      Long totalCount, Long successCount, Long failedCount, Long broadFailedCount) {
    this(
        nullToZero(totalCount),
        nullToZero(successCount),
        nullToZero(failedCount),
        nullToZero(broadFailedCount));
  }

  private static long nullToZero(Long value) {
    return value == null ? 0L : value;
  }
}
