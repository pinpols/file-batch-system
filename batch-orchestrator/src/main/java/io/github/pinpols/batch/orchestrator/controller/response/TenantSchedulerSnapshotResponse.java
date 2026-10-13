package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;

/** 已持久化调度历史的稳定 HTTP 投影。 */
public record TenantSchedulerSnapshotResponse(
    Long id,
    String tenantId,
    Instant snapshotAt,
    String fairShareGroup,
    String policyCode,
    Integer activeJobs,
    Integer activePartitions,
    Integer maxJobsBase,
    Integer burstLimit,
    Integer effectiveJobCap,
    Integer groupActiveJobs,
    Integer groupMaxJobs,
    String quotaResetPolicy,
    Integer onlineWorkers,
    JsonbString detailJson) {

  public static TenantSchedulerSnapshotResponse from(TenantSchedulerSnapshotEntity entity) {
    return new TenantSchedulerSnapshotResponse(
        entity.id(),
        entity.tenantId(),
        entity.snapshotAt(),
        entity.fairShareGroup(),
        entity.policyCode(),
        entity.activeJobs(),
        entity.activePartitions(),
        entity.maxJobsBase(),
        entity.burstLimit(),
        entity.effectiveJobCap(),
        entity.groupActiveJobs(),
        entity.groupMaxJobs(),
        entity.quotaResetPolicy(),
        entity.onlineWorkers(),
        entity.detailJson());
  }
}
