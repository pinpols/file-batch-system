package io.github.pinpols.batch.orchestrator.domain.entity;

import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;

public record TenantSchedulerSnapshotEntity(
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
    JsonbString detailJson) {}
