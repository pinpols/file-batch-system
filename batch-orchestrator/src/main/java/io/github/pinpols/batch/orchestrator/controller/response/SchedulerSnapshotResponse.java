package io.github.pinpols.batch.orchestrator.controller.response;

import java.time.Instant;
import java.util.List;

public record SchedulerSnapshotResponse(
    Instant generatedAt,
    String tenantId,
    List<PolicySnapshot> policies,
    List<QueueSnapshot> queues,
    List<WorkerLoadSnapshot> workers) {
  public record PolicySnapshot(
      String policyCode,
      String fairShareGroup,
      Integer fairShareWeight,
      Integer maxRunningJobsPerTenant,
      Integer burstLimit,
      Integer partitionBurstLimit,
      String quotaResetPolicy,
      Integer quotaBurstPeakBorrowed,
      Integer quotaBurstRemaining,
      Instant quotaResetWindowStartedAt,
      Instant quotaResetWindowExpiresAt,
      Integer groupSharedMaxRunningJobs,
      long activeJobs,
      long activePartitions,
      long groupActiveJobs,
      int effectiveTenantJobCap,
      int effectiveTenantPartitionCap) {}

  public record QueueSnapshot(
      String queueCode,
      String fairShareGroup,
      Integer fairShareWeight,
      Integer maxRunningJobs,
      Integer maxRunningPartitions,
      Integer burstLimit,
      int effectiveMaxRunningJobs,
      int effectiveMaxRunningPartitions,
      String quotaResetPolicy,
      Integer quotaBurstPeakBorrowed,
      Integer quotaBurstRemaining,
      Instant quotaResetWindowStartedAt,
      Instant quotaResetWindowExpiresAt,
      Integer groupSharedMaxRunningJobs,
      long activeJobs,
      long createdPartitions,
      long waitingPartitions,
      long readyPartitions,
      long runningPartitions,
      long retryingPartitions,
      long queuedPartitions,
      long activePartitions,
      long oldestWaitingSeconds,
      int tenantWaitingSharePermille,
      int partitionSaturationPermille,
      String bottleneckReason) {}

  public record WorkerLoadSnapshot(
      String workerCode,
      String workerGroup,
      Integer currentLoad,
      Instant heartbeatAt,
      String status) {}
}
