package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;
import lombok.Builder;

/** 已持久化调度历史的稳定 HTTP 投影。 */
@Builder
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
    return TenantSchedulerSnapshotResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .snapshotAt(entity.snapshotAt())
        .fairShareGroup(entity.fairShareGroup())
        .policyCode(entity.policyCode())
        .activeJobs(entity.activeJobs())
        .activePartitions(entity.activePartitions())
        .maxJobsBase(entity.maxJobsBase())
        .burstLimit(entity.burstLimit())
        .effectiveJobCap(entity.effectiveJobCap())
        .groupActiveJobs(entity.groupActiveJobs())
        .groupMaxJobs(entity.groupMaxJobs())
        .quotaResetPolicy(entity.quotaResetPolicy())
        .onlineWorkers(entity.onlineWorkers())
        .detailJson(entity.detailJson())
        .build();
  }
}
