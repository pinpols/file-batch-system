package io.github.pinpols.batch.worker.processes.domain;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Objects;
import lombok.Builder;

/** 同一次已CLAIM任务的不可变身份；暂存代次的创建与读取必须使用同一身份。 */
@Builder
public record ProcessInvocationIdentity(
    String tenantId,
    Long taskId,
    Long jobInstanceId,
    Long jobPartitionId,
    String workerId,
    String invocationId) {

  public ProcessInvocationIdentity {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(jobInstanceId, "jobInstanceId");
    Objects.requireNonNull(jobPartitionId, "jobPartitionId");
    Objects.requireNonNull(workerId, "workerId");
    Objects.requireNonNull(invocationId, "invocationId");
    boolean hasCompleteClaimedIdentity = EmptyChecks.isNotBlank(tenantId)
        && EmptyChecks.isNotBlank(workerId)
        && EmptyChecks.isNotBlank(invocationId)
        && taskId > 0
        && jobInstanceId > 0
        && jobPartitionId > 0;
    if (!hasCompleteClaimedIdentity) {
      throw new IllegalArgumentException("Claimed process invocation identity is incomplete");
    }
  }

  @Override
  public String toString() {
    return "ProcessInvocationIdentity[taskId=" + taskId + ']';
  }
}
