package io.github.pinpols.batch.orchestrator.domain.entity;

/**
 * 资源队列在 job_partition 维度的实时积压统计。
 *
 * <p>queueCode 解析口径与 WAITING 重派一致：优先使用 partition.input_snapshot.queueCode，缺失时回退
 * job_instance.queue_code，避免 workflow 子任务被归到 workflow 自身队列。
 */
public record QueuePartitionBacklogStats(
    String queueCode,
    long createdPartitions,
    long waitingPartitions,
    long readyPartitions,
    long runningPartitions,
    long retryingPartitions,
    long oldestWaitingSeconds) {

  public QueuePartitionBacklogStats(
      String queueCode,
      Long createdPartitions,
      Long waitingPartitions,
      Long readyPartitions,
      Long runningPartitions,
      Long retryingPartitions,
      Long oldestWaitingSeconds) {
    this(
        queueCode,
        zeroIfNull(createdPartitions),
        zeroIfNull(waitingPartitions),
        zeroIfNull(readyPartitions),
        zeroIfNull(runningPartitions),
        zeroIfNull(retryingPartitions),
        zeroIfNull(oldestWaitingSeconds));
  }

  public long queuedPartitions() {
    return createdPartitions + waitingPartitions + readyPartitions + retryingPartitions;
  }

  public long activePartitions() {
    return readyPartitions + runningPartitions + retryingPartitions;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }
}
