package io.github.pinpols.batch.orchestrator.application.service.task;

import java.util.List;

/** 作业实例与分区运维操作的固定响应契约。 */
public final class InstanceManagementResults {

  private InstanceManagementResults() {}

  public record InstanceAction(
      Long id, String instanceNo, String status, Integer cancelRequestedTasks) {
    static InstanceAction status(Long id, String instanceNo, String status) {
      return new InstanceAction(id, instanceNo, status, null);
    }
  }

  public record PartitionAction(Long id, String status) {}

  public record RetryFailedPartitions(
      Long id,
      String instanceNo,
      int requested,
      int retried,
      int conflicts,
      List<Long> partitionIds) {}
}
