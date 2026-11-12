package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.query.PartitionStatusRef;
import io.github.pinpols.batch.orchestrator.domain.view.PartitionStatusSummary;
import java.util.List;
import java.util.Objects;

/**
 * 一次 worker 回报推进时使用的实例分区计数快照。
 *
 * <p>计数有两个来源：DAG 实例需要按节点计分区，已加载分区状态行，因此直接按状态行计数；非 DAG
 * 实例不加载分区行，读取 job_instance 上已维护的计数器，再在全部分区完成时用聚合摘要校验。
 */
record PartitionOutcomeCounts(
    long total, long success, long failed, long cancelled, long terminated) {

  static PartitionOutcomeCounts fromStatusRefs(List<PartitionStatusRef> refs) {
    return new PartitionOutcomeCounts(
        refs.size(),
        countStatus(refs, PartitionStatus.SUCCESS),
        countStatus(refs, PartitionStatus.FAILED),
        countStatus(refs, PartitionStatus.CANCELLED),
        countStatus(refs, PartitionStatus.TERMINATED));
  }

  static PartitionOutcomeCounts fromInstanceCounters(JobInstanceEntity instance) {
    int expected = Objects.requireNonNullElse(instance.getExpectedPartitionCount(), 0);
    int success = Objects.requireNonNullElse(instance.getSuccessPartitionCount(), 0);
    int failed = Objects.requireNonNullElse(instance.getFailedPartitionCount(), 0);
    int cancelled = Objects.requireNonNullElse(instance.getCancelledPartitionCount(), 0);
    int terminated = Objects.requireNonNullElse(instance.getTerminatedPartitionCount(), 0);
    return new PartitionOutcomeCounts(expected, success, failed, cancelled, terminated);
  }

  /** 失败、取消和终止都算作广义失败。 */
  long broadFailed() {
    return failed + cancelled + terminated;
  }

  long finished() {
    return success + broadFailed();
  }

  boolean allFinished() {
    return total > 0 && finished() == total;
  }

  /** 摘要缺失或任一计数不一致时返回 false，调用方据此判定实例进度冲突。 */
  boolean matches(PartitionStatusSummary summary) {
    return EmptyChecks.isNotNull(summary)
        && summary.totalCount() == total
        && summary.successCount() == success
        && summary.failedCount() == failed
        && summary.cancelledCount() == cancelled
        && summary.terminatedCount() == terminated
        && summary.broadFailedCount() == broadFailed();
  }

  private static long countStatus(List<PartitionStatusRef> refs, PartitionStatus status) {
    return refs.stream()
        .filter(ref -> status.code().equals(ref.partitionStatus()))
        .count();
  }
}
